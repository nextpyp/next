package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.Svg
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.core.Component
import io.kvision.core.onEvent
import io.kvision.html.Button
import io.kvision.html.Div
import js.ScreenPos
import js.clickRelativeTo
import org.w3c.dom.events.MouseEvent
import kotlin.math.sqrt


class MeasureTool private constructor(
	val imageContainerElem: Div,
	val scaler: Scaler,
	init: (MeasureTool) -> Unit = {}
) {

	companion object {

		private const val ICON_RULER = "fas fa-ruler"
		private const val ICON_POINTER = "fas fa-mouse-pointer"

		fun button(imageContainerElem: Div, scaler: Scaler, init: (MeasureTool) -> Unit = {}): Button {
			var tool: MeasureTool? = null
			return Button("", icon = ICON_RULER).apply {

				val titleStart = "Turn on Measurement Tool"
				val titleEnd = "Turn off Measurement Tool"
				title = titleStart

				setAttribute("isMeasureButton", "yup")

				onClick {
					if (tool == null) {
						// start measuring
						icon = ICON_POINTER
						title = titleEnd
						tool = MeasureTool(imageContainerElem, scaler, init)
					} else {
						// end measuring
						tool?.end()
						tool = null
						icon = ICON_RULER
						title = titleStart
					}
				}
			}
		}

		fun isButton(comp: Component): Boolean =
			comp.getAttribute("isMeasureButton") == "yup"


		fun showIn(scaleBar: ScaleBar): ((MeasureTool) -> Unit) = { measure ->

			// configure the measure tool to show the lengths using the scale bar
			measure.onLen = { lenA ->
				scaleBar.lenA = lenA
			}

			// restore the original scale bar when we're done
			measure.onEnd = {
				scaleBar.lenA = scaleBar.initialLenA
			}
		}
	}


	var onLen: (Double?) -> Unit = {}
	var onEnd: () -> Unit = {}

	/** a position, in Angstroms */
	private class APos(
		val x: Double,
		val y: Double
	) {

		fun distTo(other: APos): Double {
			val dx = this.x - other.x
			val dy = this.y - other.y
			return (dx*dx + dy*dy)
				.takeIf { it >= 0 }
				?.let { sqrt(it) }
				?: 0.0
				// no imaginary numbers allowed, even if roundoff error does weird things
		}
	}

	private fun ScreenPos.toA() =
		APos(
			x = x.normalizedToAX(scaler),
			y = y.flipNormalized().normalizedToAY(scaler)
		)

	/** returns null if the mouse is outside the container */
	private fun MouseEvent.toPos(): APos? =
		clickRelativeTo(imageContainerElem, true)
		?.toA()

	private var startMarker: PosMarker? = null
	private var finishMarker: PosMarker? = null
	private var lineMarker: LineMarker? = null

	private val svg = Svg(classes = setOf("measure-tool"))
		.apply {

			// use a normalized coordinate system
			preserveAspectRatio = "none"
			viewBox = "0 0 1 1"

			// wire up events
			onEvent {

				mousedown = { event ->
					event.stopPropagation()
					event.preventDefault()
					event.toPos()?.let { update(it) }
				}

				mousemove = { event ->
					if (startMarker != null && finishMarker == null) {
						showLine(event.toPos())
					}
				}
			}

			imageContainerElem.add(this)
		}

	init {
		init(this)
		onLen(null)
	}

	private fun update(pos: APos) {

		if (startMarker == null || finishMarker != null) {

			// start a new measure
			removeMarkers()
			startMarker = PosMarker(pos)
				.also { svg.add(it) }
			finishMarker = null

		} else if (finishMarker == null) {

			// finish the current measure
			finishMarker = PosMarker(pos)
				.also { svg.add(it) }

			showLine(pos)
		}
	}

	private fun showLine(pos: APos?) {

		lineMarker?.let { svg.remove(it) }

		val startPos = startMarker
			?.pos
			?: return
		val finishPos = pos
			?: return
		lineMarker = LineMarker(startPos, finishPos)
			.also { svg.add(it) }

		// calculate the length
		val len = startPos.distTo(finishPos)
		onLen(len)
	}

	private fun removeMarkers() {
		startMarker?.let { svg.remove(it) }
		finishMarker?.let { svg.remove(it) }
		lineMarker?.let { svg.remove(it) }
		onLen(null)
	}

	/** ends the measure tool */
	fun end() {
		imageContainerElem.remove(svg)
		onEnd()
	}


	private inner class PosMarker(val pos: APos) : Svg.G(classes = setOf("pos-marker")) {
		init {

			// make a cross centered at the pos
			val x = pos.x.aToNormalizedX(scaler)
			val y = pos.y.aToNormalizedY(scaler).flipNormalized()
			val aspect = scaler.sourceDims.width.toDouble()/scaler.sourceDims.height.toDouble()
			val radiusx = 0.01 // 1% normalized
			val radiusy = radiusx*aspect
			add(Svg.Line().apply {
				x1 = (x - radiusx).toString()
				x2 = (x + radiusx).toString()
				y1 = y.toString()
				y2 = y1
			})
			add(Svg.Line().apply {
				x1 = x.toString()
				x2 = x1
				y1 = (y - radiusy).toString()
				y2 = (y + radiusy).toString()
			})
		}
	}

	private inner class LineMarker(val p1: APos, val p2: APos) : Svg.Line(classes = setOf("line-marker")) {
		init {
			x1 = p1.x.aToNormalizedX(scaler).toString()
			y1 = p1.y.aToNormalizedY(scaler).flipNormalized().toString()
			x2 = p2.x.aToNormalizedX(scaler).toString()
			y2 = p2.y.aToNormalizedY(scaler).flipNormalized().toString()
		}
	}
}
