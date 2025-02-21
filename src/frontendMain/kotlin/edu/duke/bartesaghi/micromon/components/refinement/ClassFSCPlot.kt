package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.SizedPanel
import edu.duke.bartesaghi.micromon.components.indexToClassNum
import edu.duke.bartesaghi.micromon.services.*
import js.plotly.plot
import kotlinext.js.jsObject
import io.kvision.html.div
import io.kvision.html.span
import js.plotly.LegendClickEvent
import js.plotly.PlotData
import js.plotly.TitleFormat
import kotlinx.coroutines.delay


/** Fourier Shell Correlation? */
open class ClassFSCPlot : SizedPanel("Resolution", Storage.ClassFSCPlotSize) {

    var data: List<ReconstructionPlotsData> = emptyList()
	var selectedClasses: List<Int> = emptyList()

	var onClick: (classNum: Int) -> Unit = {}
	var onDoubleClick: (classNum: Int) -> Unit = {}

	private val container = div()

    init {

		// wire up events
        onResize = { newSize: ImageSize ->

            // save the selection in storage
            Storage.ClassFSCPlotSize = newSize

            update()
        }

		update()
    }

    fun update() {

		container.removeAll()

		container.div {
			span("FSC between half-maps")
		}

		val data = data
			.takeIf { it.isNotEmpty() }
			?: run {
				container.emptyMessage("No reconstructions to show")
				return
			}

        val colors = Palette.colors

        val tickLocations = listOf(0, 0.143, 0.5, 1)

        val plot = container.plot(

            *data.indices.map { index ->
				val classNum = index.indexToClassNum()
                jsObject<PlotData> {
                    x = data[index].fsc.map { innerIt -> 1 / innerIt[0] }.toTypedArray()
                    y = data[index].fsc.map { innerIt -> innerIt.last() }.toTypedArray()
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
                        text = "Resolution (1/A)"
                        standoff = 10
                    }
                    range = arrayOf(0, (data.maxOfOrNull { 1 / it.fsc.last()[0] /* last X value in each class */ } ?: 0.13) * 1.025)
                }
                yaxis = jsObject {
                    title = "Fourier Shell Correlation"
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

		// wire up events

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
