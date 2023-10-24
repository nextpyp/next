package js.plotly

import js.UnshadowedWidget
import kotlinext.js.*
import io.kvision.core.Container
import io.kvision.html.Div


/**
 * Creates a Plotly element.
 * See:
 *   https://plot.ly/javascript/
 *   https://plot.ly/javascript/plotlyjs-function-reference/
 */
fun Container.plot(
	vararg data: Data,
	layout: Layout? = null,
	config: Config? = null,
	classes: Set<String> = emptySet()
) = Plot(this, classes)
		.apply {
			plot(*data, layout=layout, config=config)
		}


class Plot internal constructor(
	container: Container,
	classes: Set<String> = emptySet()
) : Div(
	classes = classes
) {

	init {
		// add to the DOM immediately
		container.add(this)
	}

	/**
	 * make a sub-element that's free from external meddling,
	 * so the DOM diffs are clean
	 */
	private val widget = UnshadowedWidget()
	init {
		// add to the DOM immediately
		add(widget)
	}

	val elem = widget.elem as PlotlyHTMLElement

	fun plot(
		vararg data: Data,
		layout: Layout? = null,
		config: Config? = null,
	) {
		Plotly.plot(
			elem,
			data.map { it }.toTypedArray(),
			layout,
			config.applyDefaultConfig()
		)
	}

	fun extend(data: Data, indices: Array<Number>) {
		Plotly.extendTraces(elem, data, indices)
	}

	fun react(
		vararg data: Data,
		layout: Layout? = null,
		config: Config? = null
	) {
		Plotly.react(
			elem,
			data.map { it }.toTypedArray(),
			layout,
			config.applyDefaultConfig()
		)
	}

	fun update(
		data: Data = jsObject {},
		layout: Layout = jsObject {},
		indices: Array<Number>? = null
	) {
		Plotly.update(elem, data, layout, indices)
	}

	fun restyle(data: Data, indices: Array<Number>) {
		Plotly.restyle(elem, data, indices)
	}

	fun relayout(layout: Layout = jsObject {}) {
		Plotly.relayout(elem, layout)
	}

	fun redraw() {
		Plotly.redraw(elem)
	}

	fun purge() {
		Plotly.purge(elem)
	}

	fun onClick(callback: (PlotMouseEvent) -> Unit) {
		elem.onClick(callback)
	}

	fun onHover(callback: (PlotMouseEvent) -> Unit) {
		elem.onHover(callback)
	}

	fun onUnhover(callback: (PlotMouseEvent) -> Unit) {
		elem.onUnhover(callback)
	}

	private fun Config?.applyDefaultConfig(): Config? =
		this?.apply {
			if (toImageButtonOptions.asDynamic() == null) {
				toImageButtonOptions = jsObject {
					format = "svg"
				}
			}
		}
}
