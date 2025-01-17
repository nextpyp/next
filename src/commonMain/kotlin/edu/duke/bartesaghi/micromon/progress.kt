package edu.duke.bartesaghi.micromon


/**
 * Represents progress information written by the `tqdm` library used by pyp
 */
data class TQDMProgressInfo(
	val value: Int,
	val max: Int,
	val timeElapsed: String?,
	val timeRemaining: String?,
	val rate: Double?,
	val unit: String
) {
	companion object {

		@Suppress("RegExpRedundantEscape")
		private val isRegex = Regex("^\\r?.*( \\||: |:)?[0-9 ]{3}%\\|[# 0-9]{10}\\| \\d+/\\d+ \\[.*\\]\\r?$")
		// WARNING: Even though IntelliJ says the `\\]` is a redundant escape, it's actually necessary in Kotlin/JS land.
		//          Don't trust the IDE, it LIES!! =P
		private val rateRegex = Regex("([0-9.? ]+)([a-zA-Z/]+)")

		fun isProgress(line: String): Boolean =
			isRegex.matches(line)

		fun from(line: String): TQDMProgressInfo? {

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
			// or even, eg:
			// `Progress:  10%|#         | 10/100 [00:00<00:04, 19.96it/s]`
			// `Progress:  98%|#####8    | 88/100 [00:00<00:04, 19.96it/s]`
			// or even, eg:
			// `\rProgress:  98%|#####8    | 88/100 [00:00<00:04, 19.96it/s]`
			// or even, eg:
			// `2025-01-16 15:05:26.9869 -05:00  INFO MockPyp: 83%|########3 | 83/100 [00:00<00:05, 5.6it/s]`

			val parts = line
				.dropWhile { it == '\r' }
				.split("|")
				.lastOrNull()
				?.split(" ", "/", "[", "<", ",", "]")
				?.filter { it.isNotBlank() }
				?: return null

			// the rate and the units can't be split by a delimeter, so use a regex
			val matcher = parts.getOrNull(4)
				?.let { rateRegex.matchEntire(it) }

			return TQDMProgressInfo(
				value = parts.getOrNull(0)?.toIntOrNull() ?: return null,
				max = parts.getOrNull(1)?.toIntOrNull() ?: return null,
				timeElapsed = parts.getOrNull(2)
					.takeIf { it != "?" },
				timeRemaining = parts.getOrNull(3)
					.takeIf { it != "?" },
				rate = matcher?.groups?.get(1)?.value?.toDoubleOrNull(),
				unit = "${matcher?.groups?.get(2)?.value ?: "?"}/${parts.getOrNull(5) ?: "?"}"
			)
		}
	}

	fun matches(next: TQDMProgressInfo): Boolean =
		this.max == next.max
			&& this.value <= next.value
}


fun <T> Sequence<T>.collapseProgress(
	transformer: ((T) -> T)? = null,
	liner: (T) -> String
): Sequence<T> = object : Sequence<T> {

	val input = this@collapseProgress.iterator()
		.peeking()
	val buf = ArrayDeque<T>()

	override fun iterator(): Iterator<T> = object : Iterator<T> {

		override fun hasNext(): Boolean =
			buf.isNotEmpty() || input.hasNext()

		override fun next(): T {

			// consume any buffered lines, if available
			buf.removeFirstOrNull()
				?.let { return it }

			var progress: T? = null
			var blank: T? = null

			while (true) {

				// get the next line, if any
				val line = input
					.takeIf { input.hasNext() }
					?.next()
					?: break

				val linestr = liner(line)
				if (TQDMProgressInfo.isProgress(linestr)) {
					// this line is progress info: aggregate it with later lines if possible
					progress =
						if (transformer != null) {
							transformer(line)
						} else {
							line
						}
					blank = null
				} else if (progress != null) {
					// had progress lines before, but this line isn't progress info:
					if (linestr.isBlank() && blank == null) {
						// blank lines are sometimes between the progress messages
						// ignore up to one of them and skip to the next non-blank line
						blank = line
						continue
					} else {
						// buffer this line (and the blank, if there) for the next time and return last progress line as the aggregate
						blank?.let { buf.add(it) }
						buf.add(line)
						return progress
					}
				} else {
					// no progress, all line
					if (linestr.isBlank()) {
						// except sometimes we get blank lines *before* the first progress message,
						// so we need to look ahead to filter those out too
						val peekLine = input
							.takeIf { it.hasNext() }
							?.peek()
						if (peekLine != null && TQDMProgressInfo.isProgress(liner(peekLine))) {
							blank = peekLine
							continue
						}
					}
					return line
				}
			}

			return progress
				?: throw NoSuchElementException()
		}
	}
}


fun String.collapseProgress(): String =
	lineSequence()
		.collapseProgress { it }
		.joinToString("\n")


/**
 * remove the preceeding carriage returns from progress messages
 * apparently pyp's logging library likes to put them there
 */
fun <T> carriageReturnTrimmer(liner: (T) -> String, factory: (T, String) -> T): (T) -> T =
	{ item ->
		val line = liner(item).trim('\r')
		factory(item, line)
	}
