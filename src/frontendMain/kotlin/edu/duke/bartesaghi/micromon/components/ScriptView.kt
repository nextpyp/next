package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import io.kvision.core.onEvent
import io.kvision.form.check.CheckBox
import io.kvision.form.check.CheckBoxStyle
import io.kvision.html.*
import io.kvision.html.div


class ScriptView : Div(classes = setOf("log-view")) {

	companion object {

		private const val nowrapClassname = "nowrap"

		fun fromText(text: String) =
			ScriptView().apply {
				setScript(text)
			}
	}


	class LineElem(lineNum: Int?) : Tr(classes = setOf("log-line")) {

		val contentElem = Td()

		init {
			td {
				lineNum?.let { content = it.toString() }
			}
			add(contentElem)
		}
	}


	private val wrapCheck = CheckBox(
		value = Storage.logWrapLines ?: true,
		label = "Wrap lines"
	).apply {
		style = CheckBoxStyle.PRIMARY
		addCssClass("auto-scroll")
		onEvent {
			change = {
				Storage.logWrapLines = value
				if (value) {
					lines.removeCssClass(nowrapClassname)
				} else {
					lines.addCssClass(nowrapClassname)
				}
			}
		}
	}

	private val lines = Table()

	private var nextLineNum = 1

	init {

		// the natural state (which comes from the CSS) is wrapping
		// so if we're starting with norwap, we need to change the css here
		if (!wrapCheck.value) {
			lines.addCssClass(nowrapClassname)
		}

		// layout the elements
		div(classes = setOf("controls")) {
			add(this@ScriptView.wrapCheck)
		}
		add(lines)
	}

	fun setScript(script: String) {

		lines.removeAll()

		if (script.isBlank()) {
			content = "(the script is empty)"
			return
		} else {
			content = null
		}

		batch {
			for (text in script.lineSequence().iterator()) {
				val line = LineElem(nextLineNum++)
				line.contentElem.content = text
				lines.add(line)
			}
		}
	}
}
