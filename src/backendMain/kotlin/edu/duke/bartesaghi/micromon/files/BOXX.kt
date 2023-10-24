package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ArrayNode
import edu.duke.bartesaghi.micromon.getArrayOrThrow
import edu.duke.bartesaghi.micromon.getDoubleOrThrow
import edu.duke.bartesaghi.micromon.getIntOrThrow
import edu.duke.bartesaghi.micromon.indices
import edu.duke.bartesaghi.micromon.mongo.getListOfDocuments
import org.bson.Document


data class BOXX(
	val particles: List<Particle> = emptyList()
) {

	data class Particle(
		val x: Double,
		val y: Double,
		val w: Double,
		val h: Double,
		/** Is the particle within the bounds of the micrograph? */
		val inBounds: Int,
		/** classification? */
		val cls: Int
	)

	companion object {

		fun from(text: String): BOXX {

			// try to parse the BOXX file
			val lines = try {
				text
					.split("\n")
					.filter { it.isNotBlank() }
			} catch (t: Throwable) {
				throw RuntimeException("can't parse BOXX file:\n$text", t)
			}

			return BOXX(
				particles = lines.map { line ->
					try {

						val numbers = line
							.split(" ", "\t")
							.filter { it.isNotBlank() }

						return@map Particle(
							numbers[0].toDouble(),
							numbers[1].toDouble(),
							numbers[2].toDouble(),
							numbers[3].toDouble(),
							numbers[4].toDouble().toInt(),
							numbers[5].toDouble().toInt()
						)

					} catch (t: Throwable) {
						throw RuntimeException("can't parse BOXX line: $line", t)
					}
				}
			)
		}

		fun from(json: ArrayNode) =
			BOXX(
				particles = json.indices().map { i ->
					val jsonParticles = json.getArrayOrThrow(i, "BOXX particles")
					Particle(
						x = jsonParticles.getDoubleOrThrow(0, "BOXX particles[$i].x"),
						y = jsonParticles.getDoubleOrThrow(1, "BOXX particles[$i].y"),
						w = jsonParticles.getDoubleOrThrow(2, "BOXX particles[$i].z"),
						h = jsonParticles.getDoubleOrThrow(3, "BOXX particles[$i].h"),
						inBounds = jsonParticles.getIntOrThrow(4, "BOXX particles[$i].inBounds"),
						cls = jsonParticles.getIntOrThrow(5, "BOXX particles[$i].cls")
					)
				}
			)
	}
}

fun Document.readBOXX() =
	BOXX(getListOfDocuments("particles")?.map { it.readBOXXParticle() } ?: emptyList())

fun BOXX.toDoc() = Document().apply {
	set("particles", particles.map { it.toDoc() })
}

fun Document.readBOXXParticle() =
	BOXX.Particle(
		x = getDouble("x"),
		y = getDouble("y"),
		w = getDouble("w"),
		h = getDouble("h"),
		inBounds = getInteger("in_bounds"),
		cls = getInteger("cls")
	)

fun BOXX.Particle.toDoc() = Document().apply {
	set("x", x)
	set("y", y)
	set("w", w)
	set("h", h)
	set("in_bounds", inBounds)
	set("cls", cls)
}
