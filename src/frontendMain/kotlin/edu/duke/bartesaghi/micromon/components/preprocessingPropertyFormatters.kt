package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.toFixed
import js.nouislider.NoUiSlider
import kotlinext.js.jsObject
import kotlin.js.Date


fun doubleFormatter(precision: Int) = jsObject<NoUiSlider.Format> {
	to = { it.toFixed(precision) }
	from = { it.toDouble() }
}

fun intFormatter() = jsObject<NoUiSlider.Format> {
	to = { it.toString() }
	from = { it.toInt() }
}

fun datetimeFormatter() = jsObject<NoUiSlider.Format> {
	to = { Date(it).toLocaleString() }
	from = { 0 } // don't really need to parse date/time strings in the slider
}


class PreprocessingDataPropertyFormatter(
	val selector: (PreprocessingData) -> Double?,
	val formatter: (Double) -> String,
	val sliderFormatter: NoUiSlider.Format
) {

	val selectorFormatter: (PreprocessingData) -> String? = {
		selector(it)?.let { formatter(it) }
	}
}


inline fun <reified T> formatter(
	crossinline selector: (T) -> Double?,
	noinline formatter: (Double) -> String,
	sliderFormatter: NoUiSlider.Format
) = PreprocessingDataPropertyFormatter(
	{ prop -> selector(prop as T) },
	formatter,
	sliderFormatter
)


object PreprocessingDataPropertyFormatters {

	private val formatters = HashMap<String,PreprocessingDataPropertyFormatter>()

	operator fun get(prop: PreprocessingDataProperty): PreprocessingDataPropertyFormatter =
		formatters[prop.id]
			?: throw NoSuchElementException("no formatter configured for preprocessing data property ${prop.id}")

	private operator fun set(prop: PreprocessingDataProperty, value: PreprocessingDataPropertyFormatter) {
		formatters[prop.id] = value
	}

	init {

		// micrograph formatters

		this[MicrographProp.Time] = formatter<MicrographMetadata>(
			{ it.timestamp.toDouble() },
			{ Date(it).toLocaleString() },
			datetimeFormatter()
		)

		this[MicrographProp.CCC] = formatter<MicrographMetadata>(
			{ it.ccc },
			{ it.toFixed(2) },
			doubleFormatter(2)
		)

		this[MicrographProp.CCCC] = formatter<MicrographMetadata>(
			{ it.cccc },
			{ it.toFixed(2) },
			doubleFormatter(2)
		)

		this[MicrographProp.Defocus1] = formatter<MicrographMetadata>(
			{ it.defocus1 },
			{ it.toFixed(1) },
			doubleFormatter(1)
		)

		this[MicrographProp.Defocus2] = formatter<MicrographMetadata>(
			{ it.defocus2 },
			{ it.toFixed(1) },
			doubleFormatter(1)
		)

		this[MicrographProp.AngleAstig] = formatter<MicrographMetadata>(
			{ it.angleAstig },
			{ it.toFixed(2) },
			doubleFormatter(2)
		)

		this[MicrographProp.AverageMotion] = formatter<MicrographMetadata>(
			{ it.averageMotion },
			{ it.toFixed(2) },
			doubleFormatter(2)
		)

		this[MicrographProp.NumParticles] = formatter<MicrographMetadata>(
			{ it.numParticles?.toDouble() },
			{ it.toString() },
			intFormatter()
		)


		// tilt series formatters

		this[TiltSeriesProp.Time] = formatter<TiltSeriesData>(
			{ it.timestamp.toDouble() },
			{ Date(it).toLocaleString() },
			datetimeFormatter()
		)

		this[TiltSeriesProp.CCC] = formatter<TiltSeriesData>(
			{ it.ccc },
			{ it.toFixed(2) },
			doubleFormatter(2)
		)

		this[TiltSeriesProp.CCCC] = formatter<TiltSeriesData>(
			{ it.cccc },
			{ it.toFixed(2) },
			doubleFormatter(2)
		)

		this[TiltSeriesProp.Defocus1] = formatter<TiltSeriesData>(
			{ it.defocus1 },
			{ it.toFixed(1) },
			doubleFormatter(1)
		)

		this[TiltSeriesProp.Defocus2] = formatter<TiltSeriesData>(
			{ it.defocus2 },
			{ it.toFixed(1) },
			doubleFormatter(1)
		)

		this[TiltSeriesProp.AngleAstig] = formatter<TiltSeriesData>(
			{ it.angleAstig },
			{ it.toFixed(2) },
			doubleFormatter(2)
		)

		this[TiltSeriesProp.AverageMotion] = formatter<TiltSeriesData>(
			{ it.averageMotion },
			{ it.toFixed(2) },
			doubleFormatter(2)
		)

		this[TiltSeriesProp.NumParticles] = formatter<TiltSeriesData>(
			{ it.numParticles?.toDouble() },
			{ it.toString() },
			intFormatter()
		)
	}
}


val PreprocessingDataProperty.formatter: PreprocessingDataPropertyFormatter get() =
	PreprocessingDataPropertyFormatters[this]
