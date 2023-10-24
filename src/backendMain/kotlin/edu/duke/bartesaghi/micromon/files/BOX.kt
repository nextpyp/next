package edu.duke.bartesaghi.micromon.files

import edu.duke.bartesaghi.micromon.mongo.getListOfDocuments
import org.bson.Document


data class BOX(
	val particles: List<Particle> = emptyList()
) {

	data class Particle(
		val x: Double,
		val y: Double,
		val w: Double,
		val h: Double
	)

	companion object {

		fun from(text: String): BOX {

			// try to parse the BOX file
			val lines = try {
				text
					.split("\n")
					.filter { it.isNotBlank() }
			} catch (t: Throwable) {
				throw RuntimeException("can't parse BOX file:\n$text", t)
			}

			return BOX(
				particles = lines.map { line ->
					try {

						val numbers = line
							.split(" ", "\t")
							.filter { it.isNotBlank() }
							.map { it.toDouble() }

						return@map Particle(
							numbers[0],
							numbers[1],
							numbers[2],
							numbers[3]
						)

					} catch (t: Throwable) {
						throw RuntimeException("can't parse BOX line: $line", t)
					}
				}
			)
		}
	}
}

fun Document.readBOX() =
	BOX(getListOfDocuments("particles")?.map { it.readBOXParticle() } ?: emptyList())

fun BOX.toDoc() = Document().apply {
	set("particles", particles.map { it.toDoc() })
}

fun Document.readBOXParticle() =
	BOX.Particle(
		x = getDouble("x"),
		y = getDouble("y"),
		w = getDouble("w"),
		h = getDouble("h")
	)

fun BOX.Particle.toDoc() = Document().apply {
	set("x", x)
	set("y", y)
	set("w", w)
	set("h", h)
}
