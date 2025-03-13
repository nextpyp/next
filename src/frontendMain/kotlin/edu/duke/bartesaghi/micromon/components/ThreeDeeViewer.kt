package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.reportJsError
import edu.duke.bartesaghi.micromon.services.ImageSize
import edu.duke.bartesaghi.micromon.toBytesString
import io.kvision.core.StringPair
import io.kvision.core.onEvent
import io.kvision.form.select.SimpleSelect
import io.kvision.html.*
import io.kvision.progress.progress
import io.kvision.progress.progressNumeric
import io.kvision.toast.Toast
import io.kvision.utils.px
import js.UnshadowedWidget
import js.getHTMLElement
import js.micromonmrc.MicromonMRC
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.button
import kotlinx.html.dom.create
import org.khronos.webgl.ArrayBuffer
import org.w3c.xhr.ARRAYBUFFER
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType
import kotlin.math.min


class ThreeDeeViewer : Div() {

	data class VolumeData(
		val name: String,
		val url: String
	)

	private val _volumes = ArrayList<VolumeData>()
	var volumes: List<VolumeData>
		get() = _volumes
		set(value) {
			_volumes.clear()
			_volumes.addAll(value)
			updateDropdowns()
		}

	fun addVolume(volume: VolumeData) {
		_volumes.add(volume)
		updateDropdowns()
	}

	private var usingDoubleViews = Storage.threeJsDoubleViews ?: true
	private val renderDiv = UnshadowedWidget(classes = setOf("threeD-render"))
	private val statusDivs = Model.values()
		.map {
			Div(classes = setOf("threeD-status"))
		}

	private val renderer = MicromonMRC.Renderer()

	private enum class Model(val number: Int) {

		One(1) {

			override fun clear(viewer: ThreeDeeViewer) {
				viewer.replot(viewer.countViews(first = false))
				viewer.renderer.clearMesh1()
			}

			override fun update(viewer: ThreeDeeViewer, data: Any?) {
				viewer.replot(viewer.countViews(first = data != null))
				try {
					data?.let { viewer.renderer.createMesh1fromData(it) }
				} catch (d: dynamic) {
					reportJsError(d)
					Toast.error("Failed to build 3D model from MRC file: $d")
				}
			}
		},

		Two(2) {

			override fun clear(viewer: ThreeDeeViewer) {
				viewer.replot(viewer.countViews(second = false))
				viewer.renderer.clearMesh2()
			}

			override fun update(viewer: ThreeDeeViewer, data: Any?) {
				viewer.replot(viewer.countViews(second = data != null))
				try {
					data?.let { viewer.renderer.createMesh2fromData(it) }
				} catch (d: dynamic) {
					reportJsError(d)
					Toast.error("Failed to build 3D model from MRC file: $d")
				}
			}
		};


		fun statusDiv(viewer: ThreeDeeViewer): Div =
			viewer.statusDivs[number - 1]

		abstract fun clear(viewer: ThreeDeeViewer)
		abstract fun update(viewer: ThreeDeeViewer, data: Any?)

		fun createDropdown(viewer: ThreeDeeViewer): SimpleSelect {

			// make string keys for all the volumes
			fun Int.volKey(): String = "vol_$this"
			val volIndex = viewer.volumes
				.withIndex()
				.associate { (voli, vol) -> voli.volKey() to vol }

			val dropdown = SimpleSelect(
				label = "Choose Map $number",
				options = ArrayList<StringPair>().apply {
					add(StringPair("none", "None"))
					for ((voli, vol) in viewer.volumes.withIndex()) {
						add(StringPair(voli.volKey(), vol.name))
					}
				}
			)

			dropdown.onEvent {
				change = e@{

					val statusDiv = statusDiv(viewer)
					statusDiv.removeAll()
					statusDiv.content = ""

					val vol = volIndex[dropdown.value]
						?: run {
							clear(viewer)
							return@e
						}

					val progress = statusDiv.progress(0, 100) {
						progressNumeric(0)
					}
					val progressLabel = statusDiv.label("0%", classes = setOf("progress-label"))
					val sizeLabel = statusDiv.label("0 B", classes = setOf("size-label"))
					statusDiv.label("/")
					val totalLabel = statusDiv.label("0 B", classes = setOf("size-label"))

					// add a cancel button to abort the map download
					val cancelButton = statusDiv.button("", icon = "fas fa-ban", classes = setOf("cancel"))

					val request = XMLHttpRequest().apply {

						open("GET", vol.url, true)

						responseType = XMLHttpRequestResponseType.ARRAYBUFFER

						onload = {
							if (response != null) {

								// check the HTTP status code before trying to show the MRC file
								when (val status = status) {

									200.toShort() -> {
										statusDiv.removeAll()
										val bytes = (response as? ArrayBuffer)
											?.byteLength
											?.toLong()
											?.toBytesString()
											?: "?? B"
										statusDiv.content = "Model $number loaded ($bytes)."
										update(viewer, response)
									}

									else -> Toast.error("Failed to load MRC file: $status $statusText")
								}
							}
						}

						onprogress = {
							val contentLength: Double = if (it.lengthComputable) {
								it.total as Double
							} else {
								// See https://stackoverflow.com/questions/15097712/how-can-i-use-deflated-gzipped-content-with-an-xhr-onprogress-function/32799706
								getResponseHeader("MRC-FILE-SIZE")?.toDouble() ?: 0.0
							}
							var amountLoaded = it.loaded as Double
							if (MicromonMRC.getUserAgent().contains("Firefox")) {
								// Account for gzip, because Firefox uses the compressed length (approximate as 10% compression)
								amountLoaded *= 1.11
								amountLoaded = min(amountLoaded, contentLength)
							}
							val percentLoaded = (amountLoaded / contentLength * 100).toInt()
							progress.getFirstProgressBar()?.value = percentLoaded
							sizeLabel.content = amountLoaded.toLong().toBytesString()
							totalLabel.content = contentLength.toLong().toBytesString()
							progressLabel.content = "$percentLoaded%"
							Unit
						}

						onerror = {
							console.log("HTTP GET failed: $statusText")
						}
						ontimeout = {
							console.log("HTTP GET timeout")
						}

						send()
					}

					// wire up events
					cancelButton.onClick {
						request.abort()
						statusDiv.removeAll()
						statusDiv.content = ""
					}
				}
			}

			return dropdown
		}
	}


	private val dropdownsElem = Span(classes = setOf("model"))


	init {

		val self = this // Kotlin DSLs are dumb

		// layout the control
		add(SizedPanel(
			"Map Display",
			Storage.ThreeDViewSize ?: ImageSize.Medium,
			// the controls are too large to fit in the small size
			includeSmall = false
		).apply {

			div(classes = setOf("threeD")) {
				add(self.dropdownsElem)
				add(self.renderDiv)
				self.statusDivs.forEach {
					add(it)
				}
			}

			onResize = { newSize: ImageSize ->

				// save the selection in storage
				Storage.ThreeDViewSize = newSize

				this@ThreeDeeViewer.replot()
			}
		})

		val controlsElem = UnshadowedWidget(setOf("threeD-controls"))
		add(controlsElem)

		val toggleButton = document.create.button(classes = "btn btn-primary")
		toggleButton.innerText = if (usingDoubleViews) "Show maps overlaid" else "Show maps separately"
		toggleButton.onclick = {
			usingDoubleViews = !usingDoubleViews
			toggleButton.innerText = if (usingDoubleViews) "Show maps overlaid" else "Show maps separately"
			Storage.threeJsDoubleViews = usingDoubleViews
			renderer.setUseDoubleViews(usingDoubleViews)
			replot(countViews())
		}

		controlsElem.elem.appendChild(toggleButton)

		renderer.init(renderDiv.elem, controlsElem.elem, 0)
		renderer.setUseDoubleViews(usingDoubleViews)
	}

	private fun updateDropdowns() {
		dropdownsElem.removeAll()
		Model.values().forEach {
			dropdownsElem.add(it.createDropdown(this))
		}
	}

	private fun getSize(): Int {
		val targetWidth = Storage.ThreeDViewSize?.approxWidth ?: ImageSize.Medium.approxWidth
		val maxWidth = this.getHTMLElement()?.offsetWidth ?: (window.innerWidth - 45)
		return minOf(targetWidth, maxWidth)
	}

	private fun replot(numViews: Int = countViews()) {
		val size = (getSize() * when (numViews) {
			0 -> 1.0
			1 -> 1.0
			2 -> 0.5
			else -> throw Error("unsupported number of views: $numViews")
		}).toInt()
		renderer.updateRendererSize(size)
		renderDiv.height = size.px
	}

	/** count the number of views showing, or will be showing after the arguments are applied */
	private fun countViews(first: Boolean? = null, second: Boolean? = null, doubleViews: Boolean? = null): Int {
		var count = 0
		if (first ?: renderer.checkFirstMeshIsShowing()) {
			count += 1
		}
		if (second ?: renderer.checkSecondMeshIsShowing()) {
			count += 1
		}
		if (count == 2 && !(doubleViews ?: renderer.checkUsingDoubleViews())) {
			count = 1
		}
		return count
	}

	fun close() {
		renderer.dispose()
	}
}
