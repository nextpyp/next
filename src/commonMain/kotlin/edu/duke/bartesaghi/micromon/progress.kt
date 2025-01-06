package edu.duke.bartesaghi.micromon


/**
 * Represents progress information written by the `tqdm` library used by pyp
 */
data class TQDMProgressInfo(
	val value: Int,
	val max: Int,
	val timeElapsed: String,
	val timeRemaining: String?,
	val rate: Double?,
	val unit: String
) {
	companion object {

		private val isRegex = Regex("(^|.* \\|)[0-9 ]{3}%\\|[# ]{10}\\| \\d+/\\d+ \\[.*\\]$")
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

			val parts = line.split("|")
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
				timeElapsed = parts.getOrNull(2) ?: "?",
				timeRemaining = parts.getOrNull(3),
				rate = matcher?.groups?.get(1)?.value?.toDoubleOrNull(),
				unit = "${matcher?.groups?.get(2)?.value ?: "?"}/${parts.getOrNull(5) ?: "?"}"
			)
		}
	}

	fun matches(next: TQDMProgressInfo): Boolean =
		this.max == next.max
			&& this.value <= next.value
}
