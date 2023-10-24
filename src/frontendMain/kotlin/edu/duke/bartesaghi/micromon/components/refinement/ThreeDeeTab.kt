package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.SizedPanel
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.PathableTab
import edu.duke.bartesaghi.micromon.views.RegisterableTab
import edu.duke.bartesaghi.micromon.views.TabRegistrar
import io.kvision.core.Container
import io.kvision.core.StringPair
import io.kvision.core.onEvent
import io.kvision.form.select.simpleSelect
import io.kvision.html.*
import js.UnshadowedWidget
import js.micromonmrc.MicromonMRC
import kotlinx.browser.document
import kotlinx.html.button
import kotlinx.html.dom.create
import org.w3c.xhr.ARRAYBUFFER
import org.w3c.xhr.XMLHttpRequest
import org.w3c.xhr.XMLHttpRequestResponseType
import io.kvision.progress.progress
import io.kvision.progress.progressNumeric
import io.kvision.utils.px
import js.getHTMLElement
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import kotlin.math.min


class ThreeDeeTab(
	private val reconstructions: List<ReconstructionData>,
	private val job: JobData
) : Div(), PathableTab {

	companion object : RegisterableTab {

		const val pathFragment = "3D"

		override fun registerRoutes(register: TabRegistrar) {
			register(pathFragment) {
				null
			}
		}
	}

	private val sizedPanel = SizedPanel(
		"Map Display",
		Storage.ThreeDViewSize ?: ImageSize.Medium,
		// the controls are too large to fit in the small size
		includeSmall = false
	).apply {
		onResize = { newSize: ImageSize ->

			// save the selection in storage
			Storage.ThreeDViewSize = newSize

			this@ThreeDeeTab.replot()
		}
	}

    private var usingDoubleViews = Storage.threeJsDoubleViews ?: true
    private var renderDiv = UnshadowedWidget(classes = setOf("threeD-render"))
    private var statusDiv1 = Div(classes = setOf("threeD-status"))
    private var statusDiv2 = Div(classes = setOf("threeD-status"))

	override var onPathChange = {}
	override var isActiveTab = false

    init {

		add(sizedPanel)

		sizedPanel.div(classes = setOf("threeD")) {

            span(classes = setOf("model")) {
                this@ThreeDeeTab.createDropdown(this, isModel1 = true)
				this@ThreeDeeTab.createDropdown(this, isModel1 = false)
            }

            add(this@ThreeDeeTab.renderDiv)
            add(this@ThreeDeeTab.statusDiv1)
            add(this@ThreeDeeTab.statusDiv2)
        }

        val controlsElem = UnshadowedWidget(setOf("threeD-controls"))
        add(controlsElem)

        val toggleButton = document.create.button(classes = "btn btn-primary")
        toggleButton.innerText = if (usingDoubleViews) "Show maps overlaid" else "Show maps separately"
        toggleButton.onclick = {
            usingDoubleViews = !usingDoubleViews
            toggleButton.innerText = if (usingDoubleViews) "Show maps overlaid" else "Show maps separately"
            Storage.threeJsDoubleViews = usingDoubleViews
            MicromonMRC.setUseDoubleViews(usingDoubleViews)
            replot(countViews())
        }

		controlsElem.elem.appendChild(toggleButton)

        MicromonMRC.createViewport(renderDiv.elem, controlsElem.elem, 0)
        MicromonMRC.setUseDoubleViews(usingDoubleViews)

    }

	override fun path(): String =
		pathFragment

	// kotlin's rules for DSLs and contexts are ridiculously stupid and we can't use a Container receiver here
	private fun createDropdown(container: Container, isModel1: Boolean) {

		val dropdown = container.simpleSelect(
			label = "Choose Map " + (if (isModel1) "1" else "2"),
			options = listOf(
				StringPair(
					"none",
					"None"
				)
			) + reconstructions.mapIndexed { index, it -> index.toString() to "Class ${it.classNum}, Iter ${it.iteration}" }.reversed()
		)

		dropdown.onEvent {
			change = e@{
				val myStatusDiv = (if (isModel1) statusDiv1 else statusDiv2)
				myStatusDiv.removeAll()
				myStatusDiv.content = ""

				val index = dropdown.value ?: return@e

				if (index == "none") {
					if (isModel1) {
						replot(countViews(first = false))
						MicromonMRC.clearMesh1()
					} else {
						replot(countViews(second = false))
						MicromonMRC.clearMesh2()
					}
					return@e
				}

				val reconstruction = reconstructions[index.toInt()]
				val classNum = reconstruction.classNum
				val iteration = reconstruction.iteration

				val progress = myStatusDiv.progress(0, 100) {
					progressNumeric(0)
				}
				val progressLabel = myStatusDiv.label("0%", classes = setOf("progress-label"))
				val sizeLabel = myStatusDiv.label("0 B", classes = setOf("size-label"))
				myStatusDiv.label("/")
				val totalLabel = myStatusDiv.label("0 B", classes = setOf("size-label"))

				// add a cancel button to abort the map download
				val cancelButton = myStatusDiv.button("", icon = "fas fa-ban", classes = setOf("cancel"))

				val request = XMLHttpRequest().apply {

					open(
						"GET",
						"/kv/reconstructions/${job.jobId}/${classNum}/${iteration}/map/${MRCType.CROP.id}",
						true
					)

					responseType = XMLHttpRequestResponseType.ARRAYBUFFER

					onload = {
						if (response != null) {
							myStatusDiv.removeAll()
							myStatusDiv.content = "Model ${if (isModel1) "1" else "2"} loaded (${(response as ArrayBuffer).byteLength.toLong().toBytesString()})."
							if (isModel1) update1(response) else update2(response)
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
					myStatusDiv.removeAll()
					myStatusDiv.content = ""
				}
			}
		}
	}

    private fun update1(data: Any?) {
        replot(countViews(first = data != null))
		data?.let { MicromonMRC.createMesh1fromData(it) }
    }

    private fun update2(data: Any?) {
        replot(countViews(second = data != null))
        data?.let { MicromonMRC.createMesh2fromData(it) }
    }

    private fun getSize(): Int {
		val targetWidth = Storage.ThreeDViewSize?.approxWidth ?: ImageSize.Medium.approxWidth
		val maxWidth = this.getHTMLElement()?.offsetWidth ?: (window.innerWidth - 45)
        return minOf(targetWidth, maxWidth)
    }

	/** count the number of views showing, or will be showing after the arguments are applied */
	private fun countViews(first: Boolean? = null, second: Boolean? = null, doubleViews: Boolean? = null): Int {
		var count = 0
		if (first ?: MicromonMRC.checkFirstMeshIsShowing()) {
			count += 1
		}
		if (second ?: MicromonMRC.checkSecondMeshIsShowing()) {
			count += 1
		}
		if (count == 2 && !(doubleViews ?: MicromonMRC.checkUsingDoubleViews())) {
			count = 1
		}
		return count
	}

    private fun replot(numViews: Int = countViews()) {
        val size = (getSize() * when (numViews) {
			0 -> 1.0
			1 -> 1.0
			2 -> 0.5
			else -> throw Error("unsupported number of views: $numViews")
		}).toInt()
        MicromonMRC.updateRendererSize(size)
        renderDiv.height = size.px
    }
}
