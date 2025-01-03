package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.form.check.CheckBox
import io.kvision.form.check.CheckBoxStyle
import io.kvision.html.*
import io.kvision.html.div
import io.kvision.modal.Modal
import js.ansicolor.addAnsiContent
import js.getHTMLElement
import kotlinx.coroutines.delay


/**
 * NOTE: to get the scrolling to work correctly in a popup/modal window,
 * you'll also need to set some styles on the `modal-body` part of the popup:
 *
 * display: flex;
 * flex-direction: column;
 * overflow-y: hidden;
 *
 * and also all html elements in the tree path between `modal-body` and this one
 * Isn't CSS fun?
 */
class LogView : Div(classes = setOf("log-view")) {

	companion object {

		const val maxNumFormattedLines = 1000

		private const val nowrapClassname = "nowrap"
		private const val showDebugClassname = "show-debug-info"

		fun fromText(text: String?) =
			LogView().apply {
				setLog(text)
			}

		fun showPopup(title: String, function: suspend () -> String) {

			val win = Modal(
				caption = title,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "max-height-dialog", "log-view-popup")
			)
			win.show()

			AppScope.launch {

				val loading = win.loading("Loading logs ...")
				val log = try {
					delayAtLeast(200) {
						function()
					}
				} catch (t: Throwable) {
					win.errorMessage(t)
					return@launch
				} finally {
					win.remove(loading)
				}

				val view = LogView()
				win.add(view)
				view.addMsg(log)
			}
		}
	}


	var onScroll: ((Double, Double) -> Unit)? = null


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

	private val showDebugCheck = CheckBox(
		value = Storage.logShowDebugInfo ?: false,
		label = "Show debug info"
	).apply {
		style = CheckBoxStyle.PRIMARY
		addCssClass("auto-scroll")
		onEvent {
			change = {
				Storage.logShowDebugInfo = value
				if (value) {
					lines.addCssClass(showDebugClassname)
				} else {
					lines.removeCssClass(showDebugClassname)
				}
			}
		}
	}

	private val lines = Table()
	private val scroller = Div(classes = setOf("log-scroller"))

	private var nextLineNum = 1
	private var explainedRest = false

	init {

		// the natural state (which comes from the CSS) is wrapping
		// so if we're starting with norwap, we need to change the css here
		if (!wrapCheck.value) {
			lines.addCssClass(nowrapClassname)
		}
		if (showDebugCheck.value) {
			lines.addCssClass(showDebugClassname)
		}

		// layout the elements
		div(classes = setOf("controls")) {
			add(this@LogView.wrapCheck)
			add(this@LogView.showDebugCheck)
		}
		add(scroller)
		scroller.add(lines)

		// bubble up the scroll event
		scroller.onEvent {
			scroll = e@{

				val elem = scroller.getHTMLElement() ?: return@e
				val maxScrollTop = (elem.scrollHeight - elem.clientHeight).toDouble()
				val scrollTop = elem.scrollTop

				onScroll?.invoke(scrollTop, maxScrollTop)
			}
		}
	}

	fun setLog(log: String?) {
		lines.removeAll()
		when (log) {
			null -> setPlaceholder("This log is not available")
			"" -> setPlaceholder("The log exists, but is empty")
			else -> addMsg(log)
		}
	}

	private fun setPlaceholder(msg: String?) {
		content =
			if (msg != null) {
				// TODO: empty style?
				"($msg)"
			} else {
				null
			}
	}

	fun addMsg(msg: String) {
		batch {
			setPlaceholder(null)

			val lines = msg.lineSequence().iterator()

			// show the first few lines in pretty formatting
			for (i in 0 until maxNumFormattedLines) {

				val text = lines
					.takeIf { it.hasNext() }
					?.next()
					?: break

				val line = LineElem(nextLineNum++)
				line.contentElem.addParsedLogLine(text)
				this.lines.add(line)
			}

			// for the rest, add a single "line" with the rest of the msg
			lines.asSequence().joinToString("\n")
				.takeIf { it.isNotEmpty() }
				?.let { addRest(it) }
		}
	}

	// NOTE: if you're calling this a lot, you probably want to do it in a batch {}
	fun addLine(block: (Container) -> Unit) {
		setPlaceholder(null)
		val line = LineElem(nextLineNum++)
		block(line.contentElem)
		lines.add(line)
	}

	fun addRest(text: String) {

		// show the explanation for the formatting changes, if needed
		if (!explainedRest) {
			val explanationLine = LineElem(null)
			explanationLine.contentElem.content =
				"(To save resources, only the first $maxNumFormattedLines lines of the log display with formatting.)"
			this.lines.add(explanationLine)
			explainedRest = true
		}

		val line = LineElem(nextLineNum++)
		line.contentElem.addAnsiContent(text, false)
		this.lines.add(line)
	}

	fun scrollToBottom() {
		scroller.getHTMLElement()
			?.let { elem ->

				// HACKHACK: do it a little later, since there seems to be some kind of race here
				// sometimes the element exists, but has no height for some reason
				// maybe the shadow DOM is taking too long to sync with the real DOM?
				AppScope.launch {
					delay(100)

					elem.scroll(0.0, elem.scrollHeight.toDouble())
				}
			}
	}
}


fun Container.addParsedLogLine(text: String) {

	// parse the line into columns
	val lineInfo = LineInfo.parse(text)
	if (lineInfo != null) {

		// show the line as columns
		span {
			addAnsiContent(lineInfo.prefix)
			textNode(" ")
		}
		span(classes = setOf("debug")) {
			addAnsiContent(lineInfo.debug)
			textNode(" ")
		}
		span {
			addAnsiContent(lineInfo.suffix)
		}

	} else {

		// show the line without columns
		addAnsiContent(text)
	}
}


data class LineInfo(
	val prefix: String,
	val debug: String,
	val suffix: String
) {

	companion object {

		fun parse(line: String): LineInfo? {

			// lines look like: eg,
			// `2023-10-11 10:40:42 [INFO] pyp/inout/utils/core.py:17 | Retrieving TS_01.rawtlt`
			// `2023-10-11 10:40:42 [INFO] pyp/inout/utils/core.py:17 | Retrieving TS_01.order`
			// `2023-10-11 10:40:42 [INFO] pyp_main.py:57 | Loading results took: 00h 00m 00s`
			// but there are invisible ANSI character codes in there too

			val posBracket = line
				.indexOfFirst { it == ']' }
				.takeIf { it >= 0 }
				?: return null
			val posPipe = line
				.indexOfFirst { it == '|' }
				.takeIf { it > posBracket }
				?: return null

			return LineInfo(
				prefix = line.substring(0 .. posBracket + 1).trimEnd(),
				debug = line.substring(posBracket + 1 until posPipe + 1).trimStart(),
				suffix = line.substring(posPipe + 1).trimStart()
			)
		}
	}
}
