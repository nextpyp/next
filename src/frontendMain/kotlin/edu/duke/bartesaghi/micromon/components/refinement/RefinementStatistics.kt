package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.SizedPanel
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.Div
import js.plotly.plot
import kotlinext.js.jsObject
import io.kvision.html.div
import js.plotly.Annotations
import js.plotly.Data
import kotlin.math.roundToInt


open class RefinementStatistics : SizedPanel("Refinement Statistics", Storage.PRPlotsSize) {

    var data: ReconstructionPlotsData? = null

    private val container = div()
	private val plotCaption = Div()

    private val round = { n: Double ->
        val multiplied = 100 * n
        (multiplied.roundToInt().toDouble()) / 100.0
    }

    init {

        onResize = { newSize: ImageSize ->

            // save the selection in storage
            Storage.PRPlotsSize = newSize

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

		plotCaption.content = "${data.metadata.particlesTotal.toInt().formatWithDigitGroupsSeparator()} projections"

		val cutoff = round(data.metadata.phaseResidual)

        val plotNames = arrayOf("View (Azimuth)", "Defocus", 
            "Score (cutoff=$cutoff)",
            "Occupancy (mean=${round(data.metadata.occ)})",
            "LogP (mean=${round(data.metadata.logp)})",
            "Sigma (mean=${round(data.metadata.sigma)})"
        )
        val marginBetweenX = 0.055
        val marginBetweenY = 0.2

        container.plot(

            *arrayOf(
				data.plots.rotHist,
				data.plots.defHist,
				data.plots.scoresHist,
				data.plots.occHist,
				data.plots.logpHist,
				data.plots.sigmaHist
			).mapIndexed { ploti, it ->
				jsObject<Data> {
                    x = it.n.indices
						.map { i -> (it.bins[i + 1] + it.bins[i])/2 }
						.toTypedArray()
                    y = it.n.toTypedArray()
                    type = "bar"
                    width = it.n.indices
						.map { i -> it.bins[i + 1] - it.bins[i] }
						.toTypedArray()
                    xaxis = "x${ploti + 3}"
                    yaxis = "y${ploti + 3}"
                    name = plotNames[ploti]
                }
            }.toTypedArray(),

            layout = jsObject {
                showlegend = false

                height = when (size) {
                    ImageSize.Small -> 250
                    ImageSize.Medium -> 300
                    ImageSize.Large -> 350
                }

                margin = jsObject {
                    l = 30
                    r = 10
                    b = 45
                    t = 35
                }
                xaxis3 = jsObject {
                    /**
                    title = jsObject<TitleFormat> {
                      text = if (size != ImageSize.Small) "x-axis 1 title" else ""
                      standoff = 0
                    }
                    */
                    domain = arrayOf(marginBetweenX, 0.33 - marginBetweenX)
                    anchor = "y3"
                }
                yaxis3 = jsObject {
                    // title = if (size != ImageSize.Small) "y-axis 1 title" else ""
                    domain = arrayOf(0.5 + marginBetweenY, 1)
                    anchor = "x3"
                }
                xaxis4 = jsObject {
                    /**
                    title = jsObject<TitleFormat> {
                        text = if (size != ImageSize.Small) "x-axis 2 title" else ""
                        standoff = 0
                    }
                    */
                    domain = arrayOf(0.33 + marginBetweenX, 0.67 - marginBetweenX)
                    anchor = "y4"
                }
                yaxis4 = jsObject {
                    // title = if (size != ImageSize.Small) "y-axis 2 title" else ""
                    domain = arrayOf(0.5 + marginBetweenY, 1)
                    anchor = "x4"
                }
                xaxis5 = jsObject {
                    /**
                     title = jsObject<TitleFormat> {
                        text = if (size != ImageSize.Small) "x-axis 3 title" else ""
                        standoff = 0
                    }
                    */
                    domain = arrayOf(0.67 + marginBetweenX, 1)
                    anchor = "y5"
                }
                yaxis5 = jsObject {
                    // title = if (size != ImageSize.Small) "y-axis 3 title" else ""
                    domain = arrayOf(0.5 + marginBetweenY, 1)
                    anchor = "x5"
                }
                xaxis6 = jsObject {
                    /**
                    title = jsObject<TitleFormat> {
                        text = if (size != ImageSize.Small) "x-axis 4 title" else ""
                        standoff = 0
                    }
                    */
                    domain = arrayOf(marginBetweenX, 0.33 - marginBetweenX)
                    anchor = "y6"
                }
                yaxis6 = jsObject {
                    // title = if (size != ImageSize.Small) "y-axis 4 title" else ""
                    domain = arrayOf(marginBetweenY, 0.5)
                    anchor = "x6"
                }
                xaxis7 = jsObject {
                    /**
                    title = jsObject<TitleFormat> {
                        text = if (size != ImageSize.Small) "x-axis 5 title" else ""
                        standoff = 0
                    }
                    */
                    domain = arrayOf(0.33 + marginBetweenX, 0.67 - marginBetweenX)
                    anchor = "y7"
                }
                yaxis7 = jsObject {
                    // title = if (size != ImageSize.Small) "y-axis 5 title" else ""
                    domain = arrayOf(marginBetweenY, 0.5)
                    anchor = "x7"
                }
                xaxis8 = jsObject {
                    /**
                    title = jsObject<TitleFormat> {
                        text = if (size != ImageSize.Small) "x-axis 6 title" else ""
                        standoff = 0
                    }
                    */
                    domain = arrayOf(0.67 + marginBetweenX, 1)
                    anchor = "y8"
                }
                yaxis8 = jsObject {
                    // title = if (size != ImageSize.Small) "y-axis 6 title" else ""
                    domain = arrayOf(marginBetweenY, 0.5)
                    anchor = "x8"
                }
                annotations = plotNames.mapIndexed<String, Annotations> { index, title ->
                    jsObject {
                        text = title
                        showarrow = false
                        x = 0
                        xref = "x${index + 3} domain"
                        y = 1.25
                        yref = "y${index + 3} domain"
                    }
                }.toTypedArray()
                shapes = arrayOf(
                    jsObject {
                        type = "line"
                        xref = "x5"
                        yref = "y5"
                        x0 = cutoff
                        x1 = cutoff
                        y0 = 0
                        y1 = data.plots.scoresHist.n.maxOrNull() ?: 1
                        line = jsObject {
                            color = "black"
                            width = 1
                        }
                    }
                )
            },
            config = jsObject {
                responsive = true
            }
        )
    }
}
