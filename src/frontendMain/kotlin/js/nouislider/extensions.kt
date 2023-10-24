package js.nouislider

import js.UnshadowedWidget
import js.getHTMLElementOrThrow
import io.kvision.core.Container
import io.kvision.html.Div


class NoUiSliderElem : Div() {

	/**
	 * make a sub-element that's free from external meddling,
	 * so the DOM diffs are clean
	 */
	private val widget = UnshadowedWidget()
	init {
		// add to the DOM immediately
		add(widget)
	}

	val elem get() = widget.getHTMLElementOrThrow()

	fun getSingle() = elem.noUiSlider.getSingle()
	fun getMulti() = elem.noUiSlider.getMulti()

	fun setSingle(value: Number) = elem.noUiSlider.setSingle(value)
	fun setMulti(values: Array<Number>) = elem.noUiSlider.setMulti(values)

	fun updateOptions(options: NoUiSlider.Options) = elem.noUiSlider.updateOptions(options)

	fun reset() = elem.noUiSlider.reset()

	fun onStart(block: () -> Unit) = elem.noUiSlider.onStart(block)
	fun onSlide(block: () -> Unit) = elem.noUiSlider.onSlide(block)
	fun onUpdate(block: () -> Unit) = elem.noUiSlider.onUpdate(block)
	fun onChange(block: () -> Unit) = elem.noUiSlider.onChange(block)
	fun onSet(block: () -> Unit) = elem.noUiSlider.onSet(block)
	fun onEnd(block: () -> Unit) = elem.noUiSlider.onEnd(block)
}

fun Container.noUiSlider(options: NoUiSlider.Options): NoUiSliderElem =
	NoUiSliderElem().apply {
		this@noUiSlider.add(this)
		noUiSlider.create(elem, options)
	}
