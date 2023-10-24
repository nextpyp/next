package edu.duke.bartesaghi.micromon.files


class Speeds(val gbps: List<Double>) {

	companion object {

		fun from(text: String): Speeds {

			val numbers = try {
				text
					.split("\n")
					.filter { it.isNotBlank() }
					.map {
						when (it) {
							"inf" -> Double.POSITIVE_INFINITY
							"-inf" -> Double.NEGATIVE_INFINITY
							else -> it.toDouble()
						}
					}
			} catch (t: Throwable) {
				throw RuntimeException("can't parse speed file:\n$text", t)
			}

			return Speeds(numbers)
		}
	}
}
