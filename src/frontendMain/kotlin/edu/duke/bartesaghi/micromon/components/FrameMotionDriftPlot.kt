package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.Palette
import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.services.DriftMetadata
import edu.duke.bartesaghi.micromon.services.ImageSize
import edu.duke.bartesaghi.micromon.services.MotionData
import edu.duke.bartesaghi.micromon.services.TiltExclusions
import edu.duke.bartesaghi.micromon.toFixed
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import js.plotly.Config
import js.plotly.Data
import js.plotly.Layout
import js.plotly.plot
import kotlinext.js.jsObject
import kotlin.math.pow
import kotlin.math.sqrt

const val HISTOGRAM_CAP = 50.0

class FrameMotionDriftPlot(
    val rawTiltSeriesImageSource: String,
    var myOnClick: (Int) -> Unit = {},
    var myOnClear: () -> Unit = {},
	var onSaveTiltExclusion: (Int, Boolean) -> Unit = { _, _ -> }
): SizedPanel(
    "Drift per Tilt",
    Storage.frameMotionDriftSize) {

    private val container = div()
    private var data: DriftMetadata? = null
    private var selectedIndex = -1
    private val motionPopup = MotionPlotSelectorModal()
    private val micrographPopup = ImageGridSelectorModal(rawTiltSeriesImageSource)

    init {
        onResize = { newSize: ImageSize ->

            // save the selection in storage
            Storage.frameMotionDriftSize = newSize
            replot()
        }
        replot()
    }

    fun setData(data: DriftMetadata, motionDataList: List<MotionData>, tiltExclusions: TiltExclusions?) {
        motionPopup.setData(motionDataList, data.tilts)
        this.data = data
        replot()
        if (motionDataList.isEmpty()) return // Avoid NoSuchElementException on the next line
        micrographPopup.load(data.tilts, tiltExclusions)
    }

    private fun addHeader(avgMotion: Double) {
        container.div {
            span("Average motion ${avgMotion.toFixed(2)} pixels")
        }
    }

    private fun clearSelection() {
        selectedIndex = -1
        myOnClear()
    }

    private var tiltAngleToIndexMap: MutableMap<Double, Int> = mutableMapOf()

    fun replot() {

        val data = data ?: return

        tiltAngleToIndexMap = mutableMapOf()
        data.tilts.forEachIndexed { index, it -> tiltAngleToIndexMap[it] = index }

        container.removeAll()

        val hypotenuseAverages = data.drifts.map { it.sumOf { drift -> sqrt(drift.x.pow(2) + drift.y.pow(2)) } }.toTypedArray()
        val overallAverage = hypotenuseAverages.sum() / hypotenuseAverages.size

        addHeader(overallAverage)

        fun getData(): Data = jsObject {
            // Necessary because the selected index can change
            x = data.tilts.toTypedArray()
            y = hypotenuseAverages.map { if (it == 0.0) 0.0 else it }.toTypedArray() // show some height at least
            type = "bar"
            marker = jsObject {
                color = data.tilts.indices.map { if (selectedIndex < 0 || it == selectedIndex) Palette.blue else Palette.lightgray }.toTypedArray()
            }
        }

        fun getLayout(): Layout = jsObject {
            autosize = true
            showlegend = false

            height = 474

            yaxis = jsObject {
                range = arrayOf(0, minOf(hypotenuseAverages.maxOrNull() ?: HISTOGRAM_CAP, HISTOGRAM_CAP))
            }

            margin = jsObject {
                l = 30
                r = 10
                b = 45
                t = 20
            }
        }

        fun getConfig(): Config = jsObject {
            responsive = true
        }

        val plot = container.plot(
            getData(),
            layout = getLayout(),
            config = getConfig()
        )


        val clearButton = container.button("Clear selection", classes = setOf("btn", "btn-primary")).onClick {
            this.disabled = true
            clearSelection()
            plot.react(getData(), layout = getLayout(), config = getConfig())
        }.apply {
            disabled = (selectedIndex == -1)
        }

        container.button("Show all raw tilts", classes = setOf("btn", "btn-primary", "with-left-margin")).onClick {
            micrographPopup.show()
        }

        container.button("Show all trajectories", classes = setOf("btn", "btn-primary", "with-left-margin")).onClick {
            motionPopup.openModal()
        }

        fun selectIndex(index: Int) {
            selectedIndex = index
            plot.react(getData(), layout = getLayout(), config = getConfig())
            myOnClick(index)
            clearButton.disabled = false
        }

        motionPopup.onSelect = { selectIndex(it) }
        micrographPopup.onSelect = { selectIndex(it) }
		micrographPopup.onSaveTiltExclusion = { index, value -> onSaveTiltExclusion(index, value) }

        plot.onHover { event ->
            if (selectedIndex != -1) return@onHover // Don't redraw the plot on the right if we've already selected something
            val tilt = event.points[0].x as Double
            val index = tiltAngleToIndexMap.getOrElse(tilt) { 0 }
            myOnClick(index)
        }
        plot.onClick { event ->
            val tilt = event.points[0].x as Double
            val index = tiltAngleToIndexMap.getOrElse(tilt) { 0 }
            selectIndex(index)
        }
    }

}