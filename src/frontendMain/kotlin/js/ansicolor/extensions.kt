package js.ansicolor

import io.kvision.core.Container
import io.kvision.html.Span
import js.JsIterator


fun Container.addAnsiContent(text: String, format: Boolean = true) {
	if (format) {
		JsIterator<SegmentInfo>(Ansicolor.parse(text))
			.asSequence()
			.map { it.toSpan() }
			.forEach { add(it) }
	} else {
		add(Span(text.stripAnsi()))
	}
}

fun String.stripAnsi(): String =
	replace(ansiControlPattern, "")

private val ansiControlPattern = Regex("\\[\\d+m")

fun SegmentInfo.toSpan(): Span {

	val span = Span()
	span.content = text

	// attempt to parse the CSS string into something KVision can understand
	// (just so it can render it back into the original CSS string again ... *sigh*)
	for (nameValue in css.split(';')) {
		val parts = nameValue.split(':')
			.map { it.trim() }
		if (parts.size < 2) {
			continue
		}
		span.setStyle(parts[0], parts[1])
	}

	return span
}
