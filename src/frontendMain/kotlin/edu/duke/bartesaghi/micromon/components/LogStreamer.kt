package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.form.check.CheckBox
import io.kvision.form.check.CheckBoxStyle
import io.kvision.html.*
import io.kvision.utils.perc
import js.Intl
import js.ansicolor.addAnsiContent
import js.ansicolor.stripAnsi
import kotlinext.js.jsObject
import kotlin.js.Date
import kotlin.js.RegExp


class LogStreamer(
	initialMsg: RealTimeC2S,
	showPinButton: Boolean = false
) : Div(classes = setOf("log-streamer")) {

	// add scroll lock button, to keep the bottom of the log in view
	val autoScroll = CheckBox(
		value = true,
		label = "Scroll to bottom on new messages"
	).apply {
		style = CheckBoxStyle.PRIMARY
		addCssClass("auto-scroll")
	}

	private val resultType = Span(classes = setOf("result"))

	private fun setResultType(value: ClusterJobResultType?) {
		resultType.removeAll()
		resultType.span("Result: ")
		when (value) {
			ClusterJobResultType.Success -> "fas fa-check-circle"
			ClusterJobResultType.Failure -> "fas fa-exclamation-triangle"
			ClusterJobResultType.Canceled -> "fas fa-ban"
			null -> null
		}?.let {
			resultType.iconStyled(it, classes = setOf("icon"))
		}
		resultType.span(value?.name ?: "(none)")
	}

	private val exitCode = Span()

	private fun setExitCode(value: Int?) {
		exitCode.content = "Exit Code: ${value ?: "(none)"}"
	}

	val pinButton = Button(
		"", "fas fa-thumbtack",
		classes = setOf("pin-button")
	).apply {
		title = "Pin this log to keep it visible while also doing other things."
		onClick {
			onPin?.invoke()
		}
	}

	var onEnd: (() -> Unit)? = null
	var onPin: (() -> Unit)? = null

	private var lastScrollTop: Double? = null

	private val logView = LogView()
	private var progressBar: TQDMProgressBar? = null

	init {
		setResultType(null)
		setExitCode(null)

		logView.onScroll = { scrollTop, maxScrollTop ->

			// where did we scroll from/to?
			val isBottom = scrollTop == maxScrollTop
			val wasBottom = lastScrollTop == maxScrollTop

			lastScrollTop = scrollTop

			if (!wasBottom && isBottom) {
				// we scrolled back to the bottom, turn auto scroll back on
				autoScroll.value = true
			} else if (wasBottom && !isBottom) {
				// we scrolled away from the bottom, turn off auto scroll
				autoScroll.value = false
			}
		}
	}

	private fun Long.timestampToString(): String {

		val dateParts = Intl.DateTimeFormat("en", jsObject {
			year = "numeric"
			month = "2-digit"
			day = "2-digit"
			hour = "2-digit"
			hourCycle = "h24"
			minute = "2-digit"
			second = "2-digit"
		}).formatToParts(Date(this))

		val partsMap = dateParts.associate {
			// formatToParts returns a list of JS Objects, like  { type: "year", value: "2022" }
			// we would rather them be a map, e.g. 'year' to '2022'
			it.type to it.value
		}

		val year   = partsMap["year"]
		val month  = partsMap["month"]
		val day    = partsMap["day"]
		val hour   = partsMap["hour"]
		val minute = partsMap["minute"]
		val second = partsMap["second"]

		return "$year-$month-$day $hour:$minute:$second"
	}

	private fun addMsgs(messages: List<StreamLogMsg>) {

		batch {
			val msgIter = messages.iterator()
			while (msgIter.hasNext()) {

				// stop formatting long logs after a few lines, to save browser resources
				if (!logView.shouldFormatNextLine()) {
					logView.addRest(msgIter.asSequence().joinToString("\n") { msg ->

						val timestamp = msg.timestamp.timestampToString()
						val level = Level[msg.level].name.uppercase()
						val path = msg.path.stripAnsi()
						val line = msg.line

						"$timestamp [$level] $path:$line ${msg.msg}"
					})
					break
				}

				val msg = msgIter.next()

				// render the message metadata as, eg:
				// 2022-07-07 15:40:47 [<span style="color: #xxxxxx;">INFO</span>] <b>pyp/inout/utils/core.py</b>:22 | Retrieving .pyp_config.toml

				when (Level[msg.level]) {

					// use special rendering for progress lines
					Level.Progress -> renderProgress(msg)

					// otherwise, render the line as usual
					else -> renderLine(msg)
				}
			}
		}

		// apply auto scroll if needed
		if (autoScroll.value) {
			scrollToBottom()
		}
	}

	private fun renderLine(msg: StreamLogMsg) {

		// get the first line of the message and write the message header
		val iter = msg.msg.lineSequence().iterator()
		val firstLine = iter
			.takeIf { it.hasNext() }
			?.next()
			?: return

		val level = Level[msg.level]

		logView.addLine { lineElem ->
			lineElem.span(msg.timestamp.timestampToString())
			lineElem.span(" [")
			lineElem.span(level.name.uppercase()) {
				when (level) {
					Level.Critical,
					Level.Error -> 0xcc0000
					Level.Warning -> 0xcc6600
					Level.Info,
					Level.Debug -> 0x00cc00
					else -> null
				}?.let {
					colorHex = it
				}
			}
			lineElem.span("] ")
			lineElem.span(classes = setOf("debug")) {
				addAnsiContent(msg.path)
				span(":${msg.line} | ")
			}
			lineElem.span(firstLine)
		}

		// add the rest of the lines in this message, if any (usually there aren't)
		for (line in iter) {
			logView.addLine { lineElem ->
				lineElem.span(line)
			}
		}
	}

	private fun renderProgress(msg: StreamLogMsg) {

		val info = TQDMProgressInfo.from(msg.msg)
			?: run {
				console.warn("failed to parse progress info:", msg.msg)
				return
			}

		// see if the old progress bar matches
		val progressBar = progressBar
		if (progressBar?.matches(info) == true) {

			// yup, we can re-use the old one
			progressBar.info = info

		} else {

			// nope, need to make a new one
			val newBar = TQDMProgressBar(info)
			this.progressBar = newBar
			logView.addLine { lineElem ->
				lineElem.span(msg.timestamp.timestampToString())
				lineElem.add(newBar)
			}
		}
	}

	private fun end() {
		autoScroll.enabled = false
		onEnd?.invoke()
	}

	val connector = WebsocketConnector(RealTimeServices.streamLog) { signaler, input, output ->

		// tell the server we want to listen to this log
		output.sendMessage(initialMsg)

		// wait for the initial status
		val msgInit = input.receiveMessage<RealTimeS2C.StreamLogInit>()
		addMsgs(msgInit.messages)
		setResultType(msgInit.resultType)
		setExitCode(msgInit.exitCode)

		signaler.connected()

		// wait for responses from the server, if needed
		if (msgInit.resultType == null) {
			for (msg in input.messages()) {
				when (msg) {
					is RealTimeS2C.StreamLogMsgs -> addMsgs(msg.messages)
					is RealTimeS2C.StreamLogEnd -> {
						setResultType(msg.resultType)
						setExitCode(msg.exitCode)
						end()
						break
					}
					else -> Unit
				}
			}
		} else {
			// otherwise, just end now
			end()
		}
	}

	init {

		// layout the UI
		div(classes = setOf("header")) {
			add(this@LogStreamer.autoScroll)
			add(this@LogStreamer.resultType)
			add(this@LogStreamer.exitCode)
			span(classes = setOf("spacer"))
			add(WebsocketControl(this@LogStreamer.connector))
			if (showPinButton) {
				add(this@LogStreamer.pinButton)
			}
		}
		add(logView)

		// actually start the streaming connection
		connector.connect()
	}

	fun close() {
		connector.disconnect()
	}

	fun scrollToBottom() {
		logView.scrollToBottom()
	}
}


// https://docs.python.org/3/library/logging.html#logging-levels
enum class Level(val num: Int) {

	Critical(50),
	Error(40),
	Warning(30),
	Info(20),
	Debug(10),
	NotSet(0),

	/**
	 * Not a real logging level from python's `logging` library.
	 * This value is a made-up number shared only between pyp and micromon to indicate progress messages.
	 */
	Progress(-10);

	companion object {

		operator fun get(num: Int): Level =
			values()
				.firstOrNull { num >= it.num }
				?: NotSet
	}
}


private class TQDMProgressBar(initialInfo: TQDMProgressInfo) : Span(classes = setOf("tqdm-progress")) {

	var info: TQDMProgressInfo = initialInfo
		set (value) {
			field = value
			update()
		}

	private val bar = Span(classes = setOf("bar"))
	private val timeElapsed = Span(classes = setOf("elapsed"))
	private val timeRemaining = Span(classes = setOf("remaining"))
	private val rate = Span(classes = setOf("value"))
	private val unit = Span(classes = setOf("unit"))

	init {

		// Kotlin DSLs are dumb
		val me = this@TQDMProgressBar

		// layout the widget
		span(classes = setOf("box")) {
			add(me.bar)
		}
		span(classes = setOf("times")) {
			add(me.timeElapsed)
			span("<")
			add(me.timeRemaining)
		}
		span(classes = setOf("rate")) {
			add(me.rate)
			add(me.unit)
		}

		update()
	}

	fun matches(info: TQDMProgressInfo): Boolean =
		info.max == this.info.max
			&& info.value >= this.info.value

	private fun update() {

		val percent = 100.0*info.value.toDouble()/info.max.toDouble()
		bar.width = percent.perc
		bar.content = "${percent.toFixed(0)}%"

		timeElapsed.content = info.timeElapsed
		timeRemaining.content = info.timeRemaining ?: "?"

		rate.content = info.rate?.toString() ?: "?"
		unit.content = info.unit
	}
}


/**
 * Represents progress information written by the `tqdm` library used by pyp
 */
private data class TQDMProgressInfo(
	val value: Int,
	val max: Int,
	val timeElapsed: String,
	val timeRemaining: String?,
	val rate: Double?,
	val unit: String
) {
	companion object {

		private val rateRegex = RegExp("([0-9.? ]+)([a-zA-Z/]+)")

		fun from(str: String): TQDMProgressInfo? {

			// messages look like, eg:
			// (unknown file):0 |  0%|          | 0/100 [00:00<?, ?it/s]
			// (unknown file):0 | 10%|#         | 10/100 [00:00<00:04, 19.96it/s]
			// (unknown file):0 | 20%|##        | 20/100 [00:01<00:04, 18.37it/s]
			// (unknown file):0 | 30%|###       | 30/100 [00:01<00:03, 18.78it/s]
			// (unknown file):0 | 40%|####      | 40/100 [00:02<00:03, 18.83it/s]
			// (unknown file):0 | 50%|#####     | 50/100 [00:02<00:02, 18.81it/s]
			// (unknown file):0 | 60%|######    | 60/100 [00:03<00:02, 18.90it/s]
			// (unknown file):0 | 70%|#######   | 70/100 [00:03<00:01, 19.06it/s]
			// (unknown file):0 | 80%|########  | 80/100 [00:04<00:01, 19.22it/s]
			// (unknown file):0 | 90%|######### | 90/100 [00:04<00:00, 19.21it/s]
			// (unknown file):0 |100%|##########| 100/100 [00:05<00:00, 19.16it/s]
			// (unknown file):0 |100%|##########| 100/100 [00:05<00:00, 18.98it/s]
			// or even, eg:
			//   0%|          | 0/1 [00:00<?, ?it/s]

			val parts = str.split("|")
				.lastOrNull()
				?.split(" ", "/", "[", "<", ",", "]")
				?.filter { it.isNotBlank() }
				?: return null

			// the rate and the units can't be split by a delimeter, so use a regex
			val matcher = parts.getOrNull(4)
				?.let { rateRegex.exec(it) }

			return TQDMProgressInfo(
				value = parts.getOrNull(0)?.toIntOrNull() ?: return null,
				max = parts.getOrNull(1)?.toIntOrNull() ?: return null,
				timeElapsed = parts.getOrNull(2) ?: "?",
				timeRemaining = parts.getOrNull(3),
				rate = matcher?.get(1)?.toDoubleOrNull(),
				unit = "${matcher?.get(2) ?: "?"}/${parts.getOrNull(5) ?: "?"}"
			)
		}
	}
}
