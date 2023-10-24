package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.Palette
import edu.duke.bartesaghi.micromon.batch
import edu.duke.bartesaghi.micromon.loading
import edu.duke.bartesaghi.micromon.services.AvgRotData
import edu.duke.bartesaghi.micromon.services.MotionData
import edu.duke.bartesaghi.micromon.services.TiltExclusions
import io.kvision.core.*
import io.kvision.form.check.checkBox
import io.kvision.form.check.CheckBoxStyle
import io.kvision.html.Div
import io.kvision.html.br
import io.kvision.html.div
import io.kvision.modal.Modal
import io.kvision.utils.perc
import js.getHTMLElement
import js.plotly.Config
import js.plotly.Data
import js.plotly.Layout
import js.plotly.plot
import kotlinext.js.jsObject
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

const val modalHeightMargin = 198 // = 2 * 40 + 2 * 16 + 30 + 2 * 12 + 2 * 16
const val modalWidthMargin = 112  // = 2 * 40 + 2 * 16

class WidthAndHeight(
    val width: Int,
    val height: Int
)

/**
 * These are the dimensions used by PYP when creating tiled images.
 * Creates an image that is very nearly square.
 */
private fun calculateTilingDimensions(count: Int): WidthAndHeight {
    val width = ceil(sqrt(count.toDouble())).toInt()
    val height = ceil(count.toDouble() / width).toInt()
    return WidthAndHeight(width, height)
}

/**
 * Determines the best way to fill a rectangular space with square images.
 */
private fun calculateOptimalTilingGivenAspect(tileCount: Int, aspectRatio: Double): WidthAndHeight {
    var numRows = 1
    var maxTileSize = 0.0
    var lastNumRows = 0
    var lastNumCols = 0
    while (numRows <= tileCount) {
        val numCols = ceil(tileCount.toDouble() / numRows).toInt()
        val tileSize = min(aspectRatio / numCols, 1.0 / numRows)
        if (tileSize < maxTileSize) break
        if (tileSize > maxTileSize) {
            maxTileSize = tileSize
            lastNumRows = numRows
            lastNumCols = numCols
        }
        numRows += 1
    }
    return WidthAndHeight(lastNumCols, lastNumRows)
}

class ImageGridSelectorModal(
    private val imageSrc: String,
    caption: String = "Select a tilt angle to view",
    loadingText: String = "Loading data...",
    private val lockAspectRatio: Boolean = true,
    var onSelect: (Int) -> Unit = {},
	var onSaveTiltExclusion: (Int, Boolean) -> Unit = { _, _ -> }
): Modal(
    caption = caption,
    escape = true,
    closeButton = true,
    classes = setOf("dashboard-popup", "full-height-dialog", "full-width-dialog")
) {

    init {
        loading(loadingText)
    }

    fun load(tilts: List<Double>, tiltExclusions: TiltExclusions?) {

        removeAll()
        div(classes = setOf("modal-selection-container") + if (lockAspectRatio) setOf("aspect-locked") else setOf("aspect-unlocked") ) {

            val tileCount = tilts.size

            val divs = (tilts.indices).map { index ->
                val imageDiv = ImageDiv(100.0, 100.0)
                val loading = imageDiv.loading("Loading image...")
                val sprite = SpriteImage(imageSrc, tileCount, index)
                sprite.img.onEvent {
                    load = {
                        imageDiv.remove(loading)
                    }
                }
                imageDiv.add(sprite)

                val hoverDiv = HoverSelectorDiv().apply {
                    onClick {
						this@ImageGridSelectorModal.hide()
                        onSelect(index)
                    }
                }

                hoverDiv.setAttribute("data-tilt-angle", ((tilts[index]*10.0).roundToInt()/10.0).toString())

                add(hoverDiv)
                hoverDiv.add(imageDiv)
                
                return@map hoverDiv
            }

			// add checkboxes to filter the tilts
			// NOTE: We can't nest the checkboxes inside the HoverSelectorDivs,
			// because there's no way to prevent the hover effects of the parent when the child is hovered.
			// As of early 2023 that is, since Firefox doesn't support the :has css selector.
			val filterChecks = tilts.indices
				.takeIf { tiltExclusions != null }
				?.map { index ->

					val check = checkBox(
						tiltExclusions?.exclusionsByTiltIndex?.get(index) ?: false,
						label = "ignore"
					).apply {
                        style = CheckBoxStyle.PRIMARY
                    }

                    check.addCssClass("filter-check")
					check.onEvent {
						click = { event ->

							// save the filter change
							onSaveTiltExclusion(index, check.value)
						}
					}

					return@map check
				}

            fun doTiling() {
                val aspect = (window.innerWidth - modalWidthMargin).toDouble() / (window.innerHeight - modalHeightMargin)
                val tiling = calculateOptimalTilingGivenAspect(tileCount, aspect)

                val tilesHorizontal = tiling.width
                val tilesVertical = tiling.height

                if (lockAspectRatio) this@div.setStyle("aspect-ratio", "$tilesHorizontal/$tilesVertical")

				val widthPercent = (100.0 / tilesHorizontal)
				val heightPercent = (100.0 / tilesVertical)

				batch {
					for (y in 0 until tilesVertical) {
						for (x in 0 until tilesHorizontal) {

							val leftPercent = widthPercent*x
							val topPercent = heightPercent*y

							val index = y * tilesHorizontal + x
							if (index >= tileCount) break

							divs[index].setSizeAndPosition(
								leftPercent,
								topPercent,
								widthPercent,
								heightPercent
							)

							filterChecks?.get(index)?.apply {
								// place the check over the lower-right corner of the div
								right = (widthPercent*(tilesHorizontal - x - 1)).perc
								bottom = (heightPercent*(tilesVertical - y - 1)).perc
							}
						}
					}
				}
            }

            doTiling()
            window.addEventListener("resize", { doTiling() })
        }
    }

    class ImageDiv(myWidth: Double, myHeight: Double): Div(classes = setOf("modal-selection-image")) {
        init {
            width = CssSize(myWidth, UNIT.perc)
            height = CssSize(myHeight, UNIT.perc)
        }
    }

    class HoverSelectorDiv : Div(classes = setOf("modal-selection-div", "holds-image")) {
        fun setSizeAndPosition(
            myLeft: Double,
            myTop: Double,
            myWidth: Double,
            myHeight: Double
        ) {
            left = CssSize(myLeft, UNIT.perc)
            top = CssSize(myTop, UNIT.perc)
            width = CssSize(myWidth, UNIT.perc)
            height = CssSize(myHeight, UNIT.perc)
        }
    }
}

open class PlotGridSelectorModal<T>(
    caption: String,
    val transformer: (T) -> Array<Data>,
    val config: Config = jsObject { },
    var layout: Layout = jsObject { },
    loadingText: String = "Loading all plots...",
    private val lockAspectRatio: Boolean = true,
    var onSelect: (Int) -> Unit = {}
): Modal(
    caption = caption,
    escape = true,
    closeButton = true,
    classes = setOf("dashboard-popup", "full-height-dialog", "full-width-dialog")
) {

    private var redrawPlots: () -> Unit = { }

    fun openModal() {
        this.show()
        AppScope.launch {
            // Delay a short time to allow DOM to be generated before loading Plotly
            while ((this@PlotGridSelectorModal.getHTMLElement()?.offsetWidth ?: 0) == 0) {
                delay(100)
            }
            redrawPlots()
        }
    }

    init {
        loading(loadingText)
    }

    fun setData(data: List<T>, tilts: List<Double>) {

        if (data.size != tilts.size) return

        class ReloadablePlot(val replot: () -> Unit) // This is unfortunately required because the responsiveness of Plotly plots
                                                     // does not work when the plot is not visible (e.g. in a hidden modal) :(
        val reloadablePlots = mutableListOf<ReloadablePlot>()

        removeAll()
        div(classes = setOf("modal-selection-container") + if (lockAspectRatio) setOf("aspect-locked") else setOf("aspect-unlocked") ) {

            val tileCount = data.size
            val tiling = calculateTilingDimensions(tileCount)

            val tilesHorizontal = tiling.width
            val tilesVertical = tiling.height

            if (lockAspectRatio) this@div.setStyle("aspect-ratio", "$tilesHorizontal/$tilesVertical")

            for (i in 0 until tilesVertical) {
                for (j in 0 until tilesHorizontal) {

                    val widthPercent = (100.0 / tilesHorizontal)
                    val heightPercent = (100.0 / tilesVertical)
                    val leftPercent = (100.0 / tilesHorizontal) * j
                    val topPercent = (100.0 / tilesVertical) * i

                    val index = i * tilesHorizontal + j
                    if (index >= tileCount) break

                    val plotDiv = PlotDiv(100.0, 100.0)
                    plotDiv.br()
                    plotDiv.loading("Loading plot...")
                    reloadablePlots.add(
                        ReloadablePlot {
                            plotDiv.removeAll()
                            plotDiv.plot(*transformer(data[i * tilesHorizontal + j]), config = config, layout = layout)
                        }
                    )

                    val hoverDiv = HoverSelectorDiv(
                        leftPercent,
                        topPercent,
                        widthPercent,
                        heightPercent
                    ).apply {
                        onClick {
                            this@PlotGridSelectorModal.hide()
                            onSelect(index)
                        }
                    }

                    hoverDiv.setAttribute("data-tilt-angle", ((tilts[index]*10.0).roundToInt()/10.0).toString())

                    add(hoverDiv)
                    hoverDiv.add(plotDiv)
                }
            }
        }

        redrawPlots = {
            reloadablePlots.forEach { it.replot() }
        }
    }

    class PlotDiv(myWidth: Double, myHeight: Double): Div(classes = setOf("modal-selection-plot")) {
        init {
            width = CssSize(myWidth, UNIT.perc)
            height = CssSize(myHeight, UNIT.perc)
        }
    }

    class HoverSelectorDiv(
        myLeft: Double,
        myTop: Double,
        myWidth: Double,
        myHeight: Double
    ) : Div(classes = setOf("modal-selection-div")) {
        init {
            left = CssSize(myLeft, UNIT.perc)
            top = CssSize(myTop, UNIT.perc)
            width = CssSize(myWidth, UNIT.perc)
            height = CssSize(myHeight, UNIT.perc)
        }
    }
}

class MotionPlotSelectorModal: PlotGridSelectorModal<MotionData>(
    "Select a tilt angle to view",
    transformer = {
        arrayOf(
            jsObject {
                x = it.x.toTypedArray()
                y = it.y.toTypedArray()
                type = "scatter"
                mode = "lines+markers"
                marker = jsObject {
                    color = IntArray(it.x.size) { it }
                }
            }
        )
    },
    config = jsObject {
        responsive = true
        displayModeBar = false
    },
    lockAspectRatio = false,
    layout = jsObject {
        autosize = true
        margin = jsObject {
            l = 30
            r = 30
            b = 30
            t = 30
        }
        showlegend = false
        yaxis = jsObject {
            scaleanchor = "x"
            fixedrange = true
        }
        xaxis = jsObject {
            fixedrange = true
        }
    }
)

class CTF1DPlotSelectorModal: PlotGridSelectorModal<AvgRotData>(
    "Select a tilt angle to view",
    transformer = {

        val avgrot = it

        // find the index of the min res (or the closest sample to it)
        val minFreq = 1.0/avgrot.minRes
        val iMinRes = avgrot.spatialFreq
            .withIndex()
            .minByOrNull { (_, res) -> abs(res - minFreq) }
            ?.index
            ?: 0

        val count = avgrot.spatialFreq.size
        val spatialFreq = avgrot.spatialFreq.subList(iMinRes, count).toTypedArray()

        arrayOf(jsObject {
            x = spatialFreq
            y = avgrot.avgRot.subList(iMinRes, count).toTypedArray()
            mode = "lines"
            name = "Radial average"
            showlegend = false
            line = jsObject {
                color = Palette.red
                width = 1
            }
        },
            jsObject {
                x = spatialFreq
                y = avgrot.ctfFit.subList(iMinRes, count).toTypedArray()
                mode = "lines"
                name = "Estimated CTF"
                showlegend = false
                line = jsObject {
                    color = Palette.green
                    width = 1
                }
            },
            jsObject {
                x = spatialFreq
                y = avgrot.crossCorrelation.subList(iMinRes, count).toTypedArray()
                mode = "lines"
                name = "Quality Fit"
                showlegend = false
                line = jsObject {
                    color = Palette.blue
                    width = 1
                }
            })

    },
    layout = jsObject {
        autosize = true
        yaxis = jsObject {
            range = arrayOf(0.0, 1.2)
            fixedrange = true
        }
        xaxis = jsObject {
            fixedrange = true
        }
        margin = jsObject {
            l = 30
            r = 30
            b = 30
            t = 30
        }
    },
    config = jsObject {
        responsive = true
        displayModeBar = false
    },
    lockAspectRatio = false
)
