package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.median
import edu.duke.bartesaghi.micromon.services.PreprocessingData
import edu.duke.bartesaghi.micromon.toFixed
import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.div
import js.getHTMLElementOrThrow
import js.plotly.*
import kotlinext.js.jsObject
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.browser.document
import kotlinx.html.*
import kotlinx.html.dom.create


class PreprocessingPlots<T:PreprocessingData>(
	val data: List<T>,
	val goto: (List<T>, Int) -> Unit,
	val tooltipImageUrl: (T) -> String
) : Div(classes = setOf("preprocessing-plots")) {

	companion object {
		private const val plotDefocusLabel = "Mean Defocus (A)"
		private const val plotResolutionLabel = "Estimated Resolution (A)"
		private const val plotAverageMotionLabel = "Average Motion (pixels)"
		private const val plotParticlesLabel = "Number of Particles"
	}

	private var plotCtfDist = null as Plot?
	private var plotResolutionDist = null as Plot?
	private var plotDf1vsDf2 = null as Plot?
	private var plotAstigmatism = null as Plot?
	private var plotDefocus = null as Plot?
	private var plotResolution = null as Plot?
	private var plotAverageMotion = null as Plot?
	private var plotParticles = null as Plot?

	inner class SortedData<S:Comparable<S>>(
		selector: (T) -> S?
	) {

		val indexedValues: List<Pair<Int,S>> = data
			.mapIndexedNotNull { index, datum ->
				selector(datum)?.let { value ->
					index to value
				}
			}

		val indexedValuesSorted: List<Pair<Int,S>> = indexedValues
			.sortedBy { (_, value) -> value }
	}

	/**
	 * Call once to initialize the hyper gallery.
	 * This component must be in the real DOM or an error will be thrown.
	 */
	fun load() {

		// if we're not in the real DOM yet, the plots won't be sized correctly
		// so throw an error now to make the problem obvious
		getHTMLElementOrThrow()

		// space out the elements a bit with coroutines, so the UI seems more responsive
		var delayMs = 0L
		fun launchSpaced(block: () -> Unit) {
			delayMs += 100
			val myDelayMs = delayMs
			AppScope.launch {
				delay(myDelayMs)
				block()
			}
		}

		removeAll()

		// make the flex box for the top plots before making the plots
		// otherwise, Plotly draws the plots at the wrong size
		val divTop = div(classes = setOf("top-plots"))
		val divCtf = divTop.div(classes = setOf("container-ctfdist"))
		val divRes = divTop.div(classes = setOf("container-resdist"))
		val divDfs = divTop.div(classes = setOf("container-df1vsdf2"))
		val divAst = divTop.div(classes = setOf("container-astig"))

		launchSpaced {
			plotCtfDist = divCtf.plotCtfDist(data, "plot-ctfdist")
		}
		launchSpaced {
			plotResolutionDist = divRes.plotResolutionDist(data, "plot-resdist")
		}
		launchSpaced {
			plotDf1vsDf2 = divDfs.plotDf1vsDf2(data, "plot-df1vsdf2")
		}
		launchSpaced {
			plotAstigmatism = divAst.plotAstigmatism(data, "plot-astig")
		}

		launchSpaced {
			plotDefocus = plotData(
				data,
				"plot-defocus",
				plotDefocusLabel
			) {
				avg(it.defocus1, it.defocus2)
			}
		}
		launchSpaced {
			plotResolution = plotData(
				data,
				"plot-resolution",
				plotResolutionLabel,
				yrange = 2.5 to min(100.0, (data.map { it.cccc }.median() ?: 2.0)*1.5)
			) {
				it.cccc
			}
		}
		launchSpaced {
			plotAverageMotion = plotData(
				data,
				"plot-averageMotion",
				plotAverageMotionLabel,
				yrange = 0.0 to (data.map { it.averageMotion }.median() ?: 1.0)*5
			) {
				it.averageMotion
			}
		}
		launchSpaced {
			plotParticles = plotData(
				data,
				"plot-particles",
				plotParticlesLabel
			) {
				it.numAutoParticles
			}
		}
	}

	fun update(datum: T) {

		// update CTF info
		plotCtfDist?.updateCtfDist(data, datum)
		plotResolutionDist?.updateResolutionDist(data, datum)
		plotDf1vsDf2?.updateDf1vsDf2(datum)
		plotAstigmatism?.updateAstigmatism(datum)
		plotDefocus?.let { plot ->
			updatePlotData(plot, data, plotDefocusLabel) { avg(it.defocus1, it.defocus2) }
		}
		plotResolution?.let { plot ->
			updatePlotData(
				plot,
				data,
				plotResolutionLabel,
				yrange = 2.5 to (data.map { it.cccc }.median() ?: 2.0)*1.5
			) { it.cccc }
		}

		// update motion info
		plotAverageMotion?.let { plot ->
			updatePlotData(
				plot,
				data,
				plotAverageMotionLabel,
				yrange = 0.0 to (data.map { it.averageMotion }.median() ?: 1.0)*5
			) { it.averageMotion }
		}

		// update particles info
		plotParticles?.let { plot ->
			updatePlotData(plot, data, plotParticlesLabel) { it.numAutoParticles }
		}
	}

	fun close() {
		// explicitly clean up the plots
		// otherwise, we get memory leaks
		// apparently the usual garbage collecton from the JS VM isn't good enough
		plotCtfDist?.purge()
		plotResolutionDist?.purge()
		plotDf1vsDf2?.purge()
		plotAstigmatism?.purge()
		plotDefocus?.purge()
		plotResolution?.purge()
		plotAverageMotion?.purge()
		plotParticles?.purge()
	}

	private fun makeCtfFilter(data: List<T>): (Double) -> Boolean {
		val cccs = data
			.map { it.ccc }
			.sorted()
		if (cccs.isNotEmpty()) {
			val median = cccs[cccs.size/2]
			val stddev = sqrt(cccs.map { it - median }.sumOf { it*it }/(cccs.size - 1).toDouble())
			val r = stddev*2
			val range = median - r .. median + r
			return { value -> value in range }
		} else {
			return { true }
		}
	}

	private fun Container.plotCtfDist(data: List<T>, classname: String) =
		plot(
			jsObject {
				x = run {
					// cut out extreme outliers
					data
						.map { it.ccc }
						.filter(makeCtfFilter(data))
						.toTypedArray()
				}
				type = "histogram"
				histnorm = "percent"
				nbinsx = 100
				marker = jsObject {
					opacity = 0.5
				}
			},

			layout = jsObject {
				title = "CTF Fit"
				titlefont = jsObject {
					size = 16
				}
				margin = jsObject {
					l = 30
					r = 10
					b = 20
					t = 40
				}
			},
			config = jsObject {
				responsive = true
			},
			classes = setOf(classname)
		)

	private fun Plot.updateCtfDist(data: List<T>, datum: T) {

		val self = this@PreprocessingPlots

		datum.ccc
			.takeIf { ccc ->
				// cut out extreme outliers
				self.makeCtfFilter(data)(ccc)
			}
			?.let { ccc ->
				extend(
					jsObject {
						x = arrayOf(arrayOf(ccc))
					},
					arrayOf(0)
				)
			}
	}

	private fun Container.plotResolutionDist(data: List<T>, classname: String) =
		plot(
			jsObject {
				x = run {
					// cut out extreme outliers
					val max = (data.map { it.cccc }.median() ?: 0.0)*1.5
					data
						.map { it.cccc }
						.filter { it <= max && it <= 100 }
						.toTypedArray()
				}
				type = "histogram"
				histnorm = "percent"
				nbinsx = 100
				marker = jsObject {
					opacity = 0.5
				}
			},

			layout = jsObject {
				title = "Est. Resolution (A)"
				titlefont = jsObject {
					size = 14
				}
				margin = jsObject {
					l = 30
					r = 10
					b = 20
					t = 40
				}
			},
			config = jsObject {
				responsive = true
			},
			classes = setOf(classname)
		)

	private fun Plot.updateResolutionDist(data: List<T>, datum: T) {
		datum.cccc
			.takeIf { cccc ->
				// cut out extreme outliers
				val max = (data.map { it.cccc }.median() ?: 0.0)*1.5
				cccc <= max && cccc <= 100
			}
			?.let { cccc ->
				extend(
					jsObject {
						x = arrayOf(arrayOf(cccc))
					},
					arrayOf(0)
				)
			}
	}

	private fun Container.plotDf1vsDf2(data: List<T>, classname: String) =
		plot(
			jsObject {
				x = data
					.map { it.defocus1 }
					.toTypedArray()
				y = data
					.map { it.defocus2 }
					.toTypedArray()
				type = "scattergl"
				mode = "markers"
				marker = jsObject {
					size = 4
					color = IntArray(data.size) { it }
				}
			},

			layout = jsObject {
				title = "Defocus 1 vs Defocus 2"
				titlefont = jsObject {
					size = 16
				}
				margin = jsObject {
					l = 30
					r = 10
					b = 20
					t = 40
				}
				yaxis = jsObject {
					scaleanchor = "x"
				}
			},
			config = jsObject {
				responsive = true
			},
			classes = setOf(classname)
		)

	private fun Plot.updateDf1vsDf2(datum: T) {
		extend(
			jsObject {
				x = arrayOf(arrayOf(datum.defocus1))
				y = arrayOf(arrayOf(datum.defocus2))
			},
			arrayOf(0)
		)
	}

	private fun Container.plotAstigmatism(data: List<T>, classname: String) =
		plot(
			jsObject {
				theta = data
					.map { it.angleAstig }
					.toTypedArray()
				r = data
					.map { dist(it.defocus1, it.defocus2) }
					.toTypedArray()
				type = "scatterpolargl"
				mode = "markers"
				marker = jsObject {
					size = 4
					color = IntArray(data.size) { it }
				}
			},

			layout = jsObject {
				title = "Angast"
				titlefont = jsObject {
					size = 16
				}
				margin = jsObject {
					l = 30
					r = 10
					b = 20
					t = 40
				}
			},
			config = jsObject {
				responsive = true
			},
			classes = setOf(classname)
		)

	private fun Plot.updateAstigmatism(datum: T) {
		extend(
			jsObject {
				theta = arrayOf(arrayOf(datum.angleAstig))
				r = arrayOf(arrayOf(datum.run { dist(defocus1, defocus2) }))
			},
			arrayOf(0)
		)
	}

	private fun <S:Comparable<S>> makePlotData(sortedData: SortedData<S>) =
		arrayOf<Data>(

			// show by time
			jsObject {
				y = sortedData.indexedValues
					.map { it.second }
					.toTypedArray()
				mode = "markers"
				marker = jsObject {
					size = 6
					color = IntArray(data.size) { it }
				}
				hovertemplate = "%{y}<extra></extra>"
				type = "scattergl"
			},

			// show by selector
			jsObject {
				y = sortedData.indexedValuesSorted
					.map { it.second }
					.toTypedArray()
				mode = "linesgl"
				line = jsObject {
					color = "rgb(128, 128,128)"
				}
			}
		)

	private fun makePlotLayout(
		label: String,
		xrange: Pair<Number,Number>? = null,
		yrange: Pair<Number,Number>? = null
	) = jsObject<Layout> {

		xaxis = jsObject {
			showspikes = false
			showticklabels = false
			showgrid = false
			if (xrange != null) {
				range = arrayOf(xrange.first, xrange.second)
			}
		}
		yaxis = jsObject {
			title = label
			titlefont = jsObject {
				size = 12
			}
			automargin = true
			if (yrange != null) {
				range = arrayOf(yrange.first, yrange.second)
			}
		}
		showlegend = false
		margin = jsObject {
			r = 0
			b = 0
			t = 20
		}
		hovermode = "closest"
	}

	private fun <S: Comparable<S>> plotData(
		data: List<T>,
		cssClass: String,
		label: String,
		yrange: Pair<Number,Number>? = null,
		selector: (T) -> S?
	): Plot {

		// sort the data using the selector
		val sortedData = SortedData(selector)

		val plot = plot(
			data = makePlotData(sortedData),
			layout = makePlotLayout(
				label,
				xrange = -1 to data.size,
				yrange = yrange
			),
			config = jsObject {
				responsive = true
			},
			classes = setOf(cssClass)
		)

		setPlotEvents(plot, sortedData)

		return plot
	}

	private fun <S: Comparable<S>> updatePlotData(
		plot: Plot,
		data: List<T>,
		label: String,
		yrange: Pair<Number,Number>? = null,
		selector: (T) -> S?
	) {

		// sort the data using the selector
		val sortedData = SortedData(selector)

		plot.react(
			data = makePlotData(sortedData),
			layout = makePlotLayout(
				label,
				xrange = -1 to data.size,
				yrange = yrange
			)
		)

		setPlotEvents(plot, sortedData)
	}

	private fun <S:Comparable<S>> setPlotEvents(plot: Plot, sortedData: SortedData<S>) {

		fun findDatumIndex(e: PlotMouseEvent): Int? {
			val point = e.points.getOrNull(0)
				?: return null
			return when (point.curveNumber.toInt()) {
				0 -> sortedData.indexedValues
				1 -> sortedData.indexedValuesSorted
				else -> null
			}?.getOrNull(point.pointIndex.toInt())?.first
		}

		// show the datum when clicking on the plot
		plot.onClick { e ->
			plot.hideDatumTooltip()
			findDatumIndex(e)
				?.let { goto(data, it) }
		}

		// show a tooltip when hovering over the plot
		plot.onHover { e ->
			findDatumIndex(e)
				?.let { plot.showDatumTooltip(data[it]) }
		}
		plot.onUnhover {
			plot.hideDatumTooltip()
		}
	}

	private fun Plot.showDatumTooltip(datum: T) {

		val self = this@PreprocessingPlots

		val html = document.create.div {
			img(src = self.tooltipImageUrl(datum))
			table("properties") {
				tr {
					td { +"Name" }
					td { +datum.id }
				}
				tr {
					td { +"Time" }
					td { +kotlin.js.Date(datum.timestamp).toLocaleString() }
				}
				tr {
					td { +"CTF Fit" }
					td { +datum.ccc.toFixed(2) }
				}
				tr {
					td { +"Est. Resolution (A)" }
					td { +datum.cccc.toFixed(2) }
				}
				tr {
					td { +"Defocus 1 (A)" }
					td { +datum.defocus1.toFixed(1) }
				}
				tr {
					td { +"Defocus 2 (A)" }
					td { +datum.defocus2.toFixed(1) }
				}
				tr {
					td { +"Angast" }
					td { +datum.angleAstig.toFixed(1) }
				}
				tr {
					td { +"Avgerage Motion (pixels)" }
					td { +datum.averageMotion.toFixed(2) }
				}
				tr {
					td { +"Number Particles" }
					td { +(datum.numAutoParticles.toString()) }
				}
			}
			div("doorstop")
		}

		// show a popup with the datum image
		// NOTE: don't use enableTooltip(), since it doesn't expose all the options we want
		// see for docs:
		// https://getbootstrap.com/docs/4.1/components/tooltips/
		getElementJQueryD()?.tooltip(jsObject {

			// override the template HTML, so we can change the CSS classes
			this.template = """
				|<div class="tooltip" role="tooltip"><div class="arrow"></div><div class="tooltip-inner tooltip-inner-micrograph"></div></div>
			""".trimMargin()

			this.trigger = "manual"
			this.html = true

			// create the HTML content for the toolip
			// NOTE: don't use KVision's shadow DOM here, use raw DOM nodes,
			// since we're dealing with Bootstrap's tooltip framework
			this.title = html
		})
		getElementJQueryD()?.tooltip("show")
	}

	private fun Plot.hideDatumTooltip() {
		getElementJQueryD()?.tooltip("hide")
		getElementJQueryD()?.tooltip("dispose")
	}
}


private fun avg(a: Double, b: Double): Double =
	(a + b)/2

private fun dist(a: Double, b: Double): Double =
	a - b
