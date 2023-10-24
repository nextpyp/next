package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.button
import kotlinext.js.jsObject
import io.kvision.html.div
import io.kvision.html.span
import js.plotly.*
import kotlin.math.abs
import kotlin.math.min

const val RESOLUTION_CAP = 10.0
const val MARGIN_BETWEEN = 0.02

open class CTFMultiTiltPlot(
    val ctf2dImageSource: String,
    var myOnClick: (Int) -> Unit = {},
    var myOnClear: () -> Unit = {}
) : SizedPanel("Tilted CTF", Storage.ctfMultiTiltSize) {

    private val container = div()
    private var data: DriftMetadata? = null
    private var selectedIndex = -1
    private var hoveredValueX: Double? = null
    private val ctf1dPopup = CTF1DPlotSelectorModal()
    private val ctf2dPopup = ImageGridSelectorModal(ctf2dImageSource)

    init {

        addHeader()

        onResize = { newSize: ImageSize ->

            // save the selection in storage
            Storage.ctfMultiTiltSize = newSize

            replot()
        }
    }

    fun setData(data: DriftMetadata) {
        this.data = data
        ctf1dPopup.setData(data.ctfProfiles, data.tilts)
        ctf2dPopup.load(data.tilts, null)
        replot()
    }

    private fun addHeader() {
        container.div {
            span("CTF per tilt angle")
        }
    }

    private fun clearSelection() {
        selectedIndex = -1
        myOnClear()
    }

    private var tiltAngleToIndexMap: MutableMap<Double, Int> = mutableMapOf()

    private val yFunctions: List<(CtfTiltValues) -> Double> = listOf(
        { (it.defocus2 + it.defocus1) / 2 },
        { it.cc },
        { it.resolution },
        { abs(it.defocus2 - it.defocus1) }
    )

    private val yColors = listOf(Palette.red, Palette.green, Palette.blue, Palette.orange)

    private val yLabels = listOf(
        "Mean Defocus",
        "Fit Score",
        "Resolution",
        "|DF1 - DF2|"
    )

    fun replot() {

        val data = data
        if (data == null) {
            container.div("(No CTF data)", classes = setOf("empty"))
            return
        }

        tiltAngleToIndexMap = mutableMapOf()
        data.tilts.forEachIndexed { index, it -> tiltAngleToIndexMap[it] = index }

        container.removeAll()
        addHeader()

        fun getData(): Array<Data> = yColors.mapIndexed<String, List<Data>> { index, myColor ->
            listOf(
                jsObject {
                    x = data.tilts.toTypedArray()
                    y = data.ctfValues.map { yFunctions[index](it) }.toTypedArray()
                    yaxis = "y${4 - index}"
                    mode = "lines+markers"
                    name = yLabels[index]
                    showlegend = selectedIndex == -1
                    line = jsObject {
                        color = myColor
                        marker = jsObject {
                            size = 12
                            color = "rgba(0, 0, 0, 0)"
                            line = jsObject {
                                color = myColor
                                width = 2
                            }
                        }
                    }
                },
                jsObject {
                    x = if (selectedIndex >= 0) arrayOf(data.tilts[selectedIndex]) else arrayOf()
                    y =
                        if (selectedIndex >= 0) arrayOf(yFunctions[index](data.ctfValues[selectedIndex])) else arrayOf()
                    yaxis = "y${4 - index}"
                    mode = "lines+markers"
                    name = yLabels[index]
                    hoverinfo = "skip"
                    showlegend = true
                    marker = jsObject {
                        size = 12
                        color = myColor
                    }
                })
        }.flatten().toTypedArray()


        fun getLayout(): Layout = jsObject {
            autosize = true
            height = 474
            xaxis = jsObject {
                title = jsObject<TitleFormat> {
                    text = "Tilt angle (degrees)"
                    standoff = 0
                }
                fixedrange = true
            }
            yaxis = jsObject {
                title = jsObject<TitleFormat> {
                    text = "\u0394DF"
                }
                domain = arrayOf(0, 0.25 - MARGIN_BETWEEN)
                fixedrange = true
            }
            yaxis2 = jsObject {
                title = jsObject<TitleFormat> {
                    text = "Resol."
                }
                domain = arrayOf(0.25 + MARGIN_BETWEEN, 0.5 - MARGIN_BETWEEN)
                range = arrayOf(2.5, min(data.ctfValues.maxOfOrNull { yFunctions[2](it) } ?: RESOLUTION_CAP, RESOLUTION_CAP))
                fixedrange = true
            }
            yaxis3 = jsObject {
                title = jsObject<TitleFormat> {
                    text = "Score"
                }
                domain = arrayOf(0.5 + MARGIN_BETWEEN, 0.75 - MARGIN_BETWEEN)
                fixedrange = true
            }
            yaxis4 = jsObject {
                title = jsObject<TitleFormat> {
                    text = "DF"
                }
                domain = arrayOf(0.75 + MARGIN_BETWEEN, 1)
                fixedrange = true
            }
            showlegend = true
            legend = jsObject {
                orientation = "h"
            }
            margin = jsObject {
                l = 60
                r = 10
                b = 10
                t = 10
            }
            if (hoveredValueX != null) {
                shapes = arrayOf(
                    jsObject {
                        type = "line"
                        xref = "x"
                        yref = "paper"
                        x0 = hoveredValueX
                        x1 = hoveredValueX
                        y0 = 0
                        y1 = 1
                        line = jsObject {
                            color = "black"
                            width = 1
                        }
                    }
                )
            }
        }

        fun getConfig(): Config = jsObject {
            responsive = true
        }

        val plot = container.plot(
            *getData(),
            layout = getLayout(),
            config = getConfig()
        )

        val clearButton = container.button("Clear selection", classes = setOf("btn", "btn-primary")).onClick {
            this.disabled = true
            clearSelection()
            plot.react(*getData(), layout = getLayout(), config = getConfig())
        }.apply {
            disabled = (selectedIndex == -1)
        }

        container.button("Show all 2D plots", classes = setOf("btn", "btn-primary", "with-left-margin")).onClick {
            ctf2dPopup.show()
        }

        container.button("Show all 1D plots", classes = setOf("btn", "btn-primary", "with-left-margin")).onClick {
            ctf1dPopup.openModal()
        }

        fun selectIndex(index: Int) {
            selectedIndex = index
            plot.react(*getData(), layout = getLayout(), config = getConfig())
            myOnClick(index)
            clearButton.disabled = false
        }

        ctf1dPopup.onSelect = { selectIndex(it) }
        ctf2dPopup.onSelect = { selectIndex(it) }

        plot.onHover { event ->
            if (selectedIndex != -1) return@onHover // Don't redraw the plot on the right if we've already selected something
            val tilt = event.points[0].x as Double
            val index = tiltAngleToIndexMap.getOrElse(tilt) { 0 }
            hoveredValueX = tilt
            plot.react(*getData(), layout = getLayout(), config = getConfig())
            myOnClick(index)
        }
        plot.onClick { event ->
            val tilt = event.points[0].x as Double
            hoveredValueX = tilt
            val index = tiltAngleToIndexMap.getOrElse(tilt) { 0 }
            selectIndex(index)
        }
    }
}