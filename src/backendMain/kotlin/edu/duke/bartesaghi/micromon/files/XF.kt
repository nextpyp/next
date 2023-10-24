package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ArrayNode
import edu.duke.bartesaghi.micromon.getArrayOrThrow
import edu.duke.bartesaghi.micromon.getDoubleOrThrow
import edu.duke.bartesaghi.micromon.indices
import edu.duke.bartesaghi.micromon.mongo.getListOfDocuments
import org.bson.Document
import kotlin.math.sqrt


data class XF(
	val samples: List<Sample> = emptyList()
) {
	data class Sample(
		// 4 parameters of a 2D transformation matrix
		val mat00: Double,
		val mat01: Double, // not sure about the transposition here though
		val mat10: Double, //
		val mat11: Double,
		val x: Double,
		val y: Double
	)

	fun averageMotion() =
		samples
			.map { sqrt(it.x*it.x + it.y*it.y) }
			.average()

	companion object {

		fun from(text: String) =
			try {
				XF(
					text
						.split("\n")
						.filter { it.isNotBlank() }
						.map { line ->
							val numbers = line.split(" ")
								.filter { it.isNotBlank() }
								.map { it.toDouble() }
							Sample(
								mat00 = numbers[0],
								mat01 = numbers[1],
								mat10 = numbers[2],
								mat11 = numbers[3],
								x = numbers[4],
								y = numbers[5]
							)
						}
				)
			} catch (t: Throwable) {
				throw RuntimeException("can't parse XF file:\n$text", t)
			}

		fun from(json: ArrayNode) =
			XF(
				samples = json.indices().map { i ->
					val jsonSamples = json.getArrayOrThrow(i, "XF samples")
					Sample(
						mat00 = jsonSamples.getDoubleOrThrow(0, "XF samples[$i].mat00"),
						mat01 = jsonSamples.getDoubleOrThrow(1, "XF samples[$i].mat01"),
						mat10 = jsonSamples.getDoubleOrThrow(2, "XF samples[$i].mat10"),
						mat11 = jsonSamples.getDoubleOrThrow(3, "XF samples[$i].mat11"),
						x = jsonSamples.getDoubleOrThrow(4, "XF samples[$i].x"),
						y = jsonSamples.getDoubleOrThrow(5, "XF samples[$i].y")
					)
				}
			)
	}
}

fun Document.readXF() =
	XF(getListOfDocuments("samples")?.map { it.readXFSample() } ?: emptyList())

fun XF.toDoc() = Document().apply {
	set("samples", samples.map { it.toDoc() })
}

fun Document.readXFSample() =
	XF.Sample(
		mat00 = getDouble("mat00"),
		mat01 = getDouble("mat01"),
		mat10 = getDouble("mat10"),
		mat11 = getDouble("mat11"),
		x = getDouble("x"),
		y = getDouble("y")
	)

fun XF.Sample.toDoc() = Document().apply {
	set("mat00", mat00)
	set("mat01", mat01)
	set("mat10", mat10)
	set("mat11", mat11)
	set("x", x)
	set("y", y)
}
