package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.SizedPanel
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.Div
import js.plotly.plot
import kotlinext.js.jsObject
import io.kvision.html.div
import js.plotly.PlotData
import js.plotly.TitleFormat


open class MaskedFSCPlot : SizedPanel("Resolution", Storage.FSCPlotSize) {

	var data: ReconstructionPlotsData? = null

	private val container = Div()

	init {

		// layout the UI
		div {
			div("Masked FSC between half-maps")
			add(this@MaskedFSCPlot.container)
		}

		onResize = { newSize: ImageSize ->

			// save the selection in storage
			Storage.FSCPlotSize = newSize

			update()
		}

		update()
	}

	fun update() {

		container.removeAll()

		val data = data
			?: run {
				container.emptyMessage("No data to show")
				return
			}

		val numberOfPlots = data.fsc[0].count() - 1
		val xValues = data.fsc.map { 1 / it[0] }.toDoubleArray()
		val yArrays = (1..numberOfPlots).map { i -> data.fsc.map { it[i] }.toDoubleArray() }.toTypedArray()
		val colors = Palette.colors

		val tickLocations = listOf(0, 0.143, 0.5, 1)

		container.plot(

			*(1..numberOfPlots).map<Int, PlotData>{
				jsObject {
					x = xValues
					y = yArrays[it - 1]
					mode = "lines"
					when (it) {
						1 -> name = "Unmasked"
						2 -> name = "Masked"
						3 -> name = "Phase randomized"
						4 -> name = "Corrected"
						else -> {
							name = "Not found!"
						}
					}
					// name = "Iteration ${it + 1}"
					line = jsObject {
						color = colors[(it - 1) % colors.size]
						width = if (it == numberOfPlots) 4 else 1.5
					}
				}
			}.toTypedArray(),

			layout = jsObject {
				height = when (size) {
					ImageSize.Small -> 250
					ImageSize.Medium -> 300
					ImageSize.Large -> 350
				}
				xaxis = jsObject {
					title = jsObject<TitleFormat> {
						text = "Resolution (1/A)"
						standoff = 0
					}
					range = arrayOf(0, xValues.last()*1.025)
				}
				yaxis = jsObject {
					title = jsObject<TitleFormat> {
						text = "Fourier Shell Correlation"
					}
					automargin = true
					range = arrayOf(-0.1, 1.05)
					tickvals = tickLocations.toTypedArray()
					ticktext = tickLocations.map { it.toString() }.toTypedArray()
				}
				showlegend = true
				legend = jsObject {
					orientation = "h"
					y = -0.2
				}
				margin = jsObject {
					l = 40
					r = 10
					b = 30
					t = 10
				}
			},
			config = jsObject {
				responsive = true
			}
		)
	}
}
