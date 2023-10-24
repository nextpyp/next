package edu.duke.bartesaghi.micromon.components


import edu.duke.bartesaghi.micromon.errorMessage
import edu.duke.bartesaghi.micromon.loading
import edu.duke.bartesaghi.micromon.pyp.MontageSizes
import edu.duke.bartesaghi.micromon.services.ImageSize
import io.kvision.core.*
import io.kvision.html.*
import io.kvision.utils.perc
import js.UnshadowedWidget
import js.getHTMLElement
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.dom.create
import kotlinx.html.js.input
import kotlinx.html.js.option
import kotlinx.html.js.select
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import kotlin.reflect.KMutableProperty0


private const val DEFAULT_FRAME_LENGTH = 100 // ms per frame (larger = slower)
private val SPEEDS = listOf(4.0, 2.0, 1.0, 0.5, 0.25)


class SpriteImage(
    imageSrc: String,
    val length: Int,
	initialIndex: Int = 0
) : Div(classes = setOf("controllable-sprite")) {

	// we can't know the montage sizes until the image loads, so leave null for now
	var montage: MontageSizes? = null
		private set

    val img = image(imageSrc)

	init {

		// wire up events
		img.onEvent {
			load = f@{

				// because Kotlin DSLs are dumb sometimes
				val sprite = this@SpriteImage

				// load the montage sizes from the raw image
				val imgElem = sprite.img.getHTMLElement() as? HTMLImageElement
					?: return@f
				val montage = MontageSizes.fromSquaredMontageImage(imgElem.naturalWidth, imgElem.naturalHeight, sprite.length)
				sprite.montage = montage

				// lock the aspect of the outer element to match the tile size
				sprite.setStyle("aspect-ratio", "${montage.tileWidth}/${montage.tileHeight}")

				// resize the image to match the container size
				sprite.img.width = (montage.tilesX*100).perc

				// update to show the current sprite index
				sprite.moveImage()
			}
		}
	}

	/** zero-based index into the (linearized) montage, in row-major order */
	var index: Int = initialIndex
		set(value) {
			field = value
			moveImage()
		}

	var x: Int = 0
		private set
	var y: Int = 0
		private set

    private fun moveImage() {
		val montage = montage
			?: return
        if (index >= this.length) {
            console.error("Attempted to move to image @ index ${index}, but only ${this.length} image(s) is/are present.")
            return
        }
        x = index % montage.tilesX
        y = index / montage.tilesX
        img.left = (-x*100).perc
        img.top = (-y*100).perc
    }
}


class PlayableSprite(
        parent: Container,
        private val sprite: SpriteImage,
        loopByDefault: Boolean = true,
        boomerangByDefault: Boolean = true,
        autoplay: Boolean = false,
        var onIndexChange: (Int?) -> Unit
) : Div() {

    private var playing = autoplay
    private var interval: Int? = null
    private var looping = loopByDefault
    private var boomerang = boomerangByDefault
    private var playingInReverse = false
    private var frameLength = DEFAULT_FRAME_LENGTH
    private var userInterruptedPlaying = false

	private val currentFrameLabel: Label
	private val slider: HTMLInputElement

    init {

        parent.add(this)

        var pause = { }
        var play: (Boolean) -> Unit = { }

        add(sprite)
        val controlsContainer = div(classes = setOf("sprite-controls-container"))
        controlsContainer.label("Image")
        currentFrameLabel = controlsContainer.label("${sprite.index + 1}", classes = setOf("current-frame-label"))
        controlsContainer.label("\u00a0of\u00a0${sprite.length}", classes = setOf("count-label"))
        val playPauseButton = controlsContainer.span(classes = setOf("fas", "fa-play")).apply {
            onClick {
                val ps = this@PlayableSprite
                if (ps.playing) pause() else play(/* immediately = */ true)
            }
        }

        slider = document.create.input().apply {
            val ps = this@PlayableSprite
            value = (sprite.index + 1).toString()
            step = "1"
            min = "1"
            max = sprite.length.toString()
            type = "range"
            className = "tomo-gif-slider"

            addEventListener("input", {
				// reassign the current index to update the UI
				navigate(currentIndex)
            })
            addEventListener("mousedown", {
                if (ps.playing) {
                    pause()
                    ps.userInterruptedPlaying = true
                }
            })
            addEventListener("mouseup", {
                if (ps.userInterruptedPlaying) {
                    if (ps.sprite.index != ps.sprite.length - 1 || ps.looping) {
                        play(/* immediately = */ false)
                    }
                    ps.userInterruptedPlaying = false
                }
            })

            val widget = UnshadowedWidget(classes = setOf("flex-grow"))
            widget.elem.appendChild(this)
            controlsContainer.add(widget)
        }

        controlsContainer.span(
            classes = setOf("fas", "fa-retweet", "sprite-checkbox") + if(loopByDefault) setOf("checked") else setOf()
        ).apply {
            title = "loop"
            onClick {
                val ps = this@PlayableSprite
                ps.looping = !ps.looping
                if (ps.looping) this.addCssClass("checked") else this.removeCssClass("checked")
            }
        }

        controlsContainer.span(
            classes = setOf("fas", "fa-exchange-alt", "sprite-checkbox") + if(boomerangByDefault) setOf("checked") else setOf()
        ).apply {
            title = "boomerang"
            onClick {
                val ps = this@PlayableSprite
                ps.boomerang = !ps.boomerang
                if (ps.boomerang) {
                    this.addCssClass("checked")
                } else {
                    this.removeCssClass("checked")
                    ps.playingInReverse = false
                }
            }
        }

        val speedSelector = document.create.select(classes = "speed-selector").apply {
            value = DEFAULT_FRAME_LENGTH.toString()
            title = "change speed"
            addEventListener("change", {
                val ps = this@PlayableSprite
                val playing = ps.playing
                if (playing) pause()
                ps.frameLength = this.value.toInt()
                if (playing) play(/* immediately = */ false)
            })
            val widget = UnshadowedWidget()
            widget.elem.appendChild(this)
            controlsContainer.add(widget)
        }

        val createOption: (String, String) -> Unit = { s1, s2 ->
            speedSelector.appendChild(
                document.create.option(content = s2).apply {
                    value = s1
                    selected = (s1 == DEFAULT_FRAME_LENGTH.toString())
                }
            )
        }

        SPEEDS.map { createOption(/* value = */ (DEFAULT_FRAME_LENGTH / it).toString(), /* label = */ it.toString() + "x\u00a0") }

        pause = {
            playPauseButton.addCssClass("fa-play")
            playPauseButton.removeCssClass("fa-pause")
            playing = false
            val interval = this.interval
            if (interval != null) window.clearInterval(interval)
        }

        play = { immediately ->
            playPauseButton.removeCssClass("fa-play")
            playPauseButton.addCssClass("fa-pause")
            playing = true

            if (sprite.index == sprite.length - 1) {
                // Deal with pressing "Play" at the end of the line
                if (!boomerang) {
                    sprite.index = -1
                }
            }

            val nextFrame = e@{
                var newIndex = if (playingInReverse) sprite.index - 1 else sprite.index + 1
                if (newIndex >= sprite.length) {
                    if (!boomerang) {
                        // Either jump to the start, or stop altogether
                        if (looping) {
                            newIndex = 0
                        } else {
                            pause()
                            return@e
                        }
                    } else {
                        playingInReverse = true
                        newIndex = sprite.index - 1
                    }
                }
                if (newIndex == -1) {
                    // Can only happen if we were playing in reverse
                    playingInReverse = false
                    if (this.looping) {
                        newIndex = 1
                    } else {
                        pause()
                        return@e
                    }
                }
                sprite.index = newIndex
                slider.value = (newIndex + 1).toString()
                currentFrameLabel.content = (newIndex + 1).toString()
                // pass the new index
                onIndexChange(newIndex)
            }

            if (immediately) nextFrame()
            interval = window.setInterval(nextFrame, this.frameLength)

        }

        if (autoplay) play(false)
    }

	/**
	 * zero-based index
	 */
	var currentIndex: Int
		get() {
			return slider.value.toInt() - 1
		}
		set(value) {

			// clamp the value
			val index = if (value < 0) {
				0
			} else if (value >= sprite.length) {
				sprite.length - 1
			} else {
				value
			}

			slider.value = "${index + 1}"
			navigate(index)
		}

	val indexRange: IntRange get() =
		0 until sprite.length

	private fun navigate(index: Int) {

		sprite.index = index
		currentFrameLabel.content = "${index + 1}"

		// keep index when someone selects a new slice
		onIndexChange(index + 1)
	}
}


class SpritePanel (
    private val imageSrc: String,
    title: String,
    private val sizeStorage: KMutableProperty0<ImageSize?>,
    private val onReadyText: String
): SizedPanel(title, sizeStorage.get() ?: ImageSize.Medium) {

    var sprite: SpriteImage? = null
    val container = div().apply {
        loading("Loading data...")
    }
    private var statusText = ""
    private var statusDiv: Div? = null
    var onReadyTextDiv: Container? = null
    var showing = false
    var loaded = false

    init {
        onResize = { newSize: ImageSize ->
            sizeStorage.set(newSize)
        }
    }

    /**
     * This function is necessary because the percentage of the image to display is dependent on the number
     * of images, which is unknown until the number of tilts has been determined.
     */
    fun load(length: Int, index: Int = 0) {
        val mySprite = SpriteImage(imageSrc, length, index)
        container.removeAll()
        val loadingElem = container.loading("Loading image...", classes = setOf("no-height"))
        var errorMessage: Container? = null
        mySprite.img.onEvent {
            load = {
                container.remove(loadingElem)
                val eMsg = errorMessage
                if (eMsg != null) container.remove(eMsg)
                if (!showing && !loaded) onReadyTextDiv = container.div(onReadyText)
                loaded = true
            }
            error = {
                container.remove(loadingElem)
                val eMsg = errorMessage
                if (eMsg != null) container.remove(eMsg)
                errorMessage = container.errorMessage("Failed to load image")
            }
        }
        val myStatus = container.div(statusText, classes = setOf("opaque-background"))
        container.add(mySprite)
        myStatus.display = Display.NONE
        mySprite.display = Display.NONE
        statusDiv = myStatus
        sprite = mySprite
    }

    fun myShow(index: Int) {
        val mySprite = sprite ?: return
        val myStatus = statusDiv
        val textDiv = onReadyTextDiv
        mySprite.index = index
        mySprite.display = Display.BLOCK
        myStatus?.display = Display.BLOCK
        if (textDiv != null) container.remove(textDiv)
        showing = true
    }

    fun setStatusText(s: String) {
        val myStatus = statusDiv
        if (myStatus != null) {
            myStatus.content = s
        } else {
            statusText = s
        }
    }
}

class PlayableSpritePanel (
    private val imageSrc: String,
    title: String,
    private val sizeStorage: KMutableProperty0<ImageSize?>,
    private val loopByDefault: Boolean = true,
    private val boomerangByDefault: Boolean = true,
    private val autoplay: Boolean = false,
    var onIndexChange: (Int?) -> Unit = {},
    var onImageSizeChange: (ImageSize) -> Unit = {}
): SizedPanel(title, sizeStorage.get() ?: ImageSize.Medium) {

    var sprite: SpriteImage? = null
    var playable: PlayableSprite? = null
    val container = div().apply {
        loading("Loading data...")
    }

    init {
        onResize = { newSize: ImageSize ->
            sizeStorage.set(newSize)
            onImageSizeChange(newSize)
        }
    }

    /**
     * This function is necessary because the percentage of the image to display is dependent on the number
     * of images, which is unknown until the number of tilts has been determined.
     */
    fun load(
		length: Int,
		index: Int,
		onDone: (sprite: SpriteImage) -> Unit = { _ -> }
	) {
        val mySprite = SpriteImage(imageSrc, length, index)
        container.removeAll()
        val loadingElem = container.loading("Loading data...", classes = setOf("no-height"))
        var errorMessage: Container? = null
        mySprite.img.onEvent {
            load = {
				// NOTE: the HTML load event apparently gets called every time the <img> element enters the DOM,
				// (even after changing tabs containing the image) because the snabbdom shadow dom is a stupid idea
				// and does incredibly non-intuitive stuff that we have to work around all the time! Arg.
				// So only run the post-image-load code once after the image actually loads,
				// not every time the shadow DOM sneezes.
				if (loadingElem.parent != null) {
					container.remove(loadingElem)
					errorMessage?.let { container.remove(it) }
					onDone(mySprite)
				}
            }
            error = {
                container.remove(loadingElem)
				errorMessage?.let { container.remove(it) }
                errorMessage = container.errorMessage("Failed to load image")
            }
        }
        sprite = mySprite
        playable = PlayableSprite(container, mySprite, loopByDefault, boomerangByDefault, autoplay, onIndexChange = this@PlayableSpritePanel.onIndexChange)
    }
}
