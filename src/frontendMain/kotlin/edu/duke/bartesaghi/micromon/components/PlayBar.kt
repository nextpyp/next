package edu.duke.bartesaghi.micromon.components

import io.kvision.core.onClick
import io.kvision.html.Div
import io.kvision.html.Label
import io.kvision.html.Span
import js.getHTMLElement
import js.unshadow
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.dom.create
import kotlinx.html.js.input
import kotlinx.html.js.option
import kotlinx.html.js.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


class PlayBar(
	initialFrames: IntRange,
	val speeds: List<Speed> = DEFAULT_SPEEDS
) : Div(classes = setOf("sprite-controls-container")) {
	// NOTE: the CSS classes here are shared with the PlayableSprite code

	companion object {
		val DEFAULT_SPEEDS = listOf(
			Speed(25.milliseconds, "4x", false),
			Speed(50.milliseconds, "2x", false),
			Speed(100.milliseconds, "1x", true),
			Speed(200.milliseconds, "0.5x", false),
			Speed(400.milliseconds, "0.25x", false)
		)
	}

	data class Speed(
		val duration: Duration,
		val label: String,
		val default: Boolean
	)


	/**
	 * Shows directly in the UI, 1-based indexing recommended
	 */
	var frames: IntRange = run {
			// need at least one frame to keep from making all the types nullable
			// so if we get an empty range, coerce it to [1]
			initialFrames
				.takeIf { !it.isEmpty() }
				?: 1 .. 1
		}
		set(value) {
			field = value
			updateSlider()
			updateLabel()
		}

	var currentFrame: Int
		get() = slider.value.toInt()
		set(value) { slider.value = "${value.coerceIn(frames)}" }

	var currentSpeed: Speed =
		speeds
			.firstOrNull { it.default }
			?: throw IllegalArgumentException("requires at least one default speed")

	var playing: Boolean = false
		set(value) {
			field = value
			if (field) {
				playPauseButton.removeCssClass("fa-play")
				playPauseButton.addCssClass("fa-pause")
			} else {
				playPauseButton.removeCssClass("fa-pause")
				playPauseButton.addCssClass("fa-play")
			}
		}

	var looping: Boolean = false
		set(value) {
			field = value
			if (field) {
				loopButton.addCssClass("checked")
			} else {
				loopButton.removeCssClass("checked")
			}

			// reset the frame counter so the looping can continue
			counter = -1
		}

	var boomeranging: Boolean = false
		set(value) {
			field = value
			if (field) {
				boomerangButton.addCssClass("checked")
			} else {
				boomerangButton.removeCssClass("checked")
			}

			// reset the frame counter so the boomerang starts at the beginning
			counter = -1
		}

	// counts frames for the animation controller
	private var counter = -1

	private var interval: Int? = null

	var onFrame: ((frame: Int) -> Unit) = {}

	private val label = Label(classes = setOf("count-label"))

	private val playPauseButton = Span(classes = setOf("fas", "fa-play")).apply {
		onClick {
			val bar = this@PlayBar
			if (bar.playing) {
				bar.pause()
			} else {
				bar.play()
			}
		}
	}

	private val slider = document.create.input().apply {

		step = "1"
		min = "1"
		type = "range"
		className = "tomo-gif-slider"

		addEventListener("input", {
			// NOTE: called when the slider changes
			updateLabel()
			counter = currentFrame - frames.first()
			onFrame(currentFrame)
		})
		addEventListener("mousedown", {
			pause()
		})
	}

	private val loopButton = Span(
		classes = setOf("fas", "fa-retweet", "sprite-checkbox")
	).apply {
		title = "loop"
		onClick {
			val bar = this@PlayBar
			bar.looping = !bar.looping
		}
	}

	private val boomerangButton = Span(
		classes = setOf("fas", "fa-exchange-alt", "sprite-checkbox")
	).apply {
		title = "boomerang"
		onClick {
			val bar = this@PlayBar
			bar.boomeranging = !bar.boomeranging
		}
	}

	private val speedSelector = document.create.select(classes = "speed-selector").apply {

		value = currentSpeed.label
		title = "change speed"

		for (speed in speeds) {
			appendChild(
				document.create.option(content = speed.label).apply {
					value = speed.label
					selected = (speed == currentSpeed)
				}
			)
		}

		addEventListener("change", f@{
			val bar = this@PlayBar
			val speed = bar.speeds
				.find { it.label == this.value }
				?: return@f
			bar.currentSpeed = speed
			if (bar.playing) {
				bar.pause()
				bar.play()
			}
		})
	}

	fun play() {

		if (playing) {
			return
		}

		playing = true

		// use a js interval as an animation controller
		val func = f@{

			// make sure we're still in the real DOM, otherwise stop playing
			if (this@PlayBar.getHTMLElement() == null) {
				pause()
				return@f
			}

			fun setFrame(f: Int) {
				currentFrame = frames.first() + f
				updateLabel()
				onFrame(currentFrame)
			}

			val numFrames = frames.last() - frames.first() + 1
			val doubledFrames = (numFrames*2 - 2)

			// advance to the next frame
			counter += 1
			if (boomeranging) {

				// double the frame count and reflect in the middle
				val c = counter % doubledFrames
				if (c < numFrames) {
					setFrame(c)
				} else {
					setFrame(doubledFrames - c)
				}
				if (!looping && counter >= doubledFrames) {
					stop()
				}

			} else {

				// wrap around to the beginning
				setFrame(counter % numFrames)
				if (!looping && counter >= numFrames - 1) {
					stop()
				}
			}
		}
		interval = window.setInterval(func, currentSpeed.duration.inWholeMilliseconds.toInt())
		func()
	}

	fun pause() {

		playing = false

		// stop the animation controller, if any
		interval?.let { window.clearInterval(it) }
		interval = null
	}

	fun stop() {
		pause()
		counter = -1
	}

	init {

		// layout the UI
		add(label)
		add(playPauseButton)
		// add the raw HTML elements to a wrapper to protect them from the shadow dom madness
		add(unshadow(slider, classes = setOf("flex-grow")))
		add(loopButton)
		add(boomerangButton)
		add(unshadow(speedSelector))

		updateSlider()
		updateLabel()
	}

	private fun updateSlider() {
		slider.value = frames.first.toString()
		slider.max = frames.last.toString()
	}

	private fun updateLabel() {
		label.content = "Image $currentFrame of ${frames.last()}"
	}
}
