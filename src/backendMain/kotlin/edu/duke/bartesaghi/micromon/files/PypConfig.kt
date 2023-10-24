package edu.duke.bartesaghi.micromon.files


class PypConfig : Iterable<Map.Entry<String,String>> {

	private val values = HashMap<String,String>()

	private fun String.normalize() = lowercase()

	fun contains(key: String) = values.containsKey(key.normalize())

	operator fun get(key: String) = values[key.normalize()]
	operator fun set(key: String, value: String) {
		values[key] = value
	}

	override fun iterator() =
		values.entries.iterator()

	companion object {

		fun from(text: String) = PypConfig().apply {

			text
				.split("\n")
				.filter { it.isNotBlank() }
				.forEach { line ->

					val parts = line
						.split("\t", " ")
						.filter { it.isNotBlank() }

					val key = parts[0]
					val value = parts.getOrNull(1) ?: ""

					values[key.normalize()] = value
				}
		}
	}
}
