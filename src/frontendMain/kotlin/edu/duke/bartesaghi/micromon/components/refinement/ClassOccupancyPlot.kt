package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.SizedPanel
import edu.duke.bartesaghi.micromon.services.*
import js.plotly.plot
import kotlinext.js.jsObject
import io.kvision.html.div
import io.kvision.html.span
import js.plotly.LegendClickEvent
import js.plotly.PlotData
import js.plotly.TitleFormat
import kotlinx.coroutines.delay


open class ClassOccupancyPlot : SizedPanel("Occupancy", Storage.ClassOccPlotSize) {

	var data: List<ReconstructionPlotsData> = emptyList()
	var selectedClasses: List<Int> = emptyList()

	var onClick: (classNum: Int) -> Unit = {}
	var onDoubleClick: (classNum: Int) -> Unit = {}

	private val container = div()

	init {

		// wire up events
        onResize = { newSize: ImageSize ->

            // save the selection in storage
            Storage.ClassOccPlotSize = newSize

            update()
        }

		update()
    }

    fun update() {

		container.removeAll()

		container.div {
			span("Particle class assignments")
		}

		val data = data
			.takeIf { it.isNotEmpty() }
			?: run {
				container.emptyMessage("No reconstructions to show")
				return
			}
        val occupancyArrays = data
			.map { it.plots.occPlot ?: emptyList() }
			.takeIf { it.any { plot -> plot.isNotEmpty() } }
			?: run {
				container.emptyMessage("No occupancy information to show for this iteration")
				return
	        }

        val colors = Palette.colors

        val plot = container.plot(

			*data.indices.map { index ->
				val classNum = index.indexToClassNum()
                jsObject<PlotData> {
                    x = occupancyArrays[index]
						.indices
						.toList()
						.toTypedArray()
                    y = occupancyArrays[index]
						.toTypedArray()
                    mode = "lines"
                    name = "Class $classNum"
                    line = jsObject {
                        color = colors[index % colors.size]
                        width = 1.5
                    }
                    visible =
						if (classNum in selectedClasses) {
							true
						} else {
							"legendonly"
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
						text = "Particles (100x)"
						standoff = 0
					}
                    range = arrayOf(0, (occupancyArrays.maxOfOrNull { it.size } ?: 100))
                }
                yaxis = jsObject {
                    title = "Percentage"
                    range = arrayOf(0, 100)
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

		var wasDoubleClick: Boolean

		plot.elem.on("plotly_legendclick") { event: LegendClickEvent ->
			wasDoubleClick = false
			AppScope.launch {
				delay(300) // Double clicks occur within 300ms according to plotly (https://plotly.com/javascript/configuration-options/)
				if (!wasDoubleClick) {
					val classNum = event.curveNumber.toInt() + 1
					onClick(classNum)
				}
			}
		}

		plot.elem.on("plotly_legenddoubleclick") { event: LegendClickEvent ->
			AppScope.launch {
				wasDoubleClick = true
				val classNum = event.curveNumber.toInt() + 1
				onDoubleClick(classNum)
			}
		}
    }
}
