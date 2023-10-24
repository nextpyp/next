package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.SizedPanel
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.Div
import js.plotly.plot
import kotlinext.js.jsObject
import io.kvision.html.div


open class OrientationDefocusPlots : SizedPanel("Orientation/Defocus Distribution", Storage.PRHistogramSize) {

    var data: ReconstructionPlotsData? = null

    private val container = div(classes = setOf("reconstruction-pr-hist"))
	private val plotCaption = Div()

    init {

		// layout the UI
		div {
			add(this@OrientationDefocusPlots.plotCaption)
			add(this@OrientationDefocusPlots.container)
		}

        onResize = { newSize: ImageSize ->

            // save the selection in storage
            Storage.PRHistogramSize = newSize

            update()
        }

		update()
    }

    fun update() {

		plotCaption.content = ""
        container.removeAll()

		val data = data
			?: run {
				container.emptyMessage("No data to show")
				return
			}

		val particlesUsed = data.metadata.particlesUsed.toInt().formatWithDigitGroupsSeparator()
		val particlesTotal = data.metadata.particlesTotal.toInt().formatWithDigitGroupsSeparator()
        plotCaption.content = "$particlesUsed from $particlesTotal projections"

		val plotHeight = when (size) {
			ImageSize.Small -> 150
			ImageSize.Medium -> 240
			ImageSize.Large -> 480
		}

        container.plot(

            jsObject {
                z = data.plots.defRotHistogram
					.map { it.toTypedArray() }.toTypedArray()
                type = "heatmap"
                colorscale = "Rainbow"
                colorbar = jsObject {
                    thickness = 15
                }
            },

            layout = jsObject {
                showlegend = false
                title = "Particle Counts"
                titlefont = jsObject {
                    size = 14
                }

				height = plotHeight

                margin = jsObject {
                    l = 60
                    r = 10
                    b = 45
                    t = 40
                }

                xaxis = jsObject {
                    title = if (size != ImageSize.Small) "Defocus Group" else ""
                    titlefont = jsObject {
                        size = 12
                    }
                }

                yaxis = jsObject {
                    scaleanchor = "x"
                    title = if (size != ImageSize.Small) "Orientation Group" else ""
                    titlefont = jsObject {
                        size = 12
                    }
                }
            },
            config = jsObject {
                responsive = true
            }
        )

        container.plot(

            jsObject {
                z = data.plots.defRotScores
					.map { it.toTypedArray() }.toTypedArray()
                type = "heatmap"
                colorscale = "Rainbow"
                colorbar = jsObject {
                    thickness = 15
                }
            },

            layout = jsObject {
                showlegend = false
                title = "Mean Score"
                titlefont = jsObject {
                    size = 14
                }

				height = plotHeight

                margin = jsObject {
                    l = 60
                    r = 10
                    b = 45
                    t = 40
                }

                xaxis = jsObject {
                    title = if (size != ImageSize.Small) "Defocus Group" else ""
                    titlefont = jsObject {
                        size = 12
                    }
                }

                yaxis = jsObject {
                    scaleanchor = "x"
                    title = if (size != ImageSize.Small) "Orientation Group" else ""
                    titlefont = jsObject {
                        size = 12
                    }
                }
            },
            config = jsObject {
                responsive = true
            }
        )
    }
}
