package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ArrayNode
import edu.duke.bartesaghi.micromon.getArrayOrThrow
import edu.duke.bartesaghi.micromon.getDoubleOrThrow
import edu.duke.bartesaghi.micromon.indices
import edu.duke.bartesaghi.micromon.mongo.getListOfDocuments
import edu.duke.bartesaghi.micromon.pyp.ArgValues
import edu.duke.bartesaghi.micromon.pyp.ctfMinResOrThrow
import edu.duke.bartesaghi.micromon.services.AvgRotData
import org.bson.Document


data class AVGROT(
	val samples: List<Sample> = emptyList()
) {

	data class Sample(

		/** spatial frequency (1/Angstroms) */
		val spatialFreq: Double,

		/** 1D rotational average of spectrum (assuming no astigmatism) */
		val avgRotNoAstig: Double,

		/** 1D rotational average of spectrum */
		val avgRot: Double,

		/** CTF fit */
		val ctfFit: Double,

		/** cross-correlation between spectrum and CTF fit */
		val crossCorrelation: Double,

		/** 2sigma of expected cross correlation of noise */
		val twoSigma: Double
	)

	companion object {

		fun from(text: String): AVGROT {

			// try to parse the file
			val lines = try {
				text
					.split("\n")
					.filter { it.isNotBlank() && !it.startsWith("#") }
			} catch (t: Throwable) {
				throw RuntimeException("can't parse AVGROT file:\n$text", t)
			}

			if (lines.size < 6) {
				throw RuntimeException("AVGROT file has fewer than 6 lines")
			}

			fun parseLine(line: String): List<Double> =
				try {
					line
						.split("\t", " ")
						.filter { it.isNotBlank() }
						.map { it.toDouble() }
				} catch (t: Throwable) {
					throw RuntimeException("can't parse AVGROT line: $line", t)
				}

			val nums1 = parseLine(lines[0])
			val nums2 = parseLine(lines[1])
			val nums3 = parseLine(lines[2])
			val nums4 = parseLine(lines[3])
			val nums5 = parseLine(lines[4])
			val nums6 = parseLine(lines[5])

			// determine the number of samples
			val numSamples = nums1.size
			if (listOf(nums2, nums3, nums4, nums5, nums6).any { it.size != numSamples }) {
				throw RuntimeException("mismatched line lengths in AVGROT file: ${listOf(nums1, nums2, nums3, nums4, nums5, nums5).map { it.size }}")
			}

			return AVGROT(
				samples = (0 until numSamples)
					.map { i ->
						Sample(
							nums1[i],
							nums2[i],
							nums3[i],
							nums4[i],
							nums5[i],
							nums6[i]
						)
					}
			)
		}

		fun from(json: ArrayNode) =
			AVGROT(
				samples = json.indices().map { i ->
					val jsonSample = json.getArrayOrThrow(i, "AVGROT samples")
					Sample(
						spatialFreq = jsonSample.getDoubleOrThrow(0, "AVGROT samples[$i}.spatialFreq"),
						avgRotNoAstig = jsonSample.getDoubleOrThrow(1, "AVGROT samples[$i}.avgRotNoAstig"),
						avgRot = jsonSample.getDoubleOrThrow(2, "AVGROT samples[$i}.avgRot"),
						ctfFit = jsonSample.getDoubleOrThrow(3, "AVGROT samples[$i}.ctfFit"),
						crossCorrelation = jsonSample.getDoubleOrThrow(4, "AVGROT samples[$i}.crossCorrelation"),
						twoSigma = jsonSample.getDoubleOrThrow(5, "AVGROT samples[$i}.twoSigma")
					)
				}
			)
	}

	fun data(pypValues: ArgValues) = AvgRotData(
		spatialFreq = samples.map { it.spatialFreq },
		avgRot = samples.map { it.avgRot },
		ctfFit = samples.map { it.ctfFit },
		crossCorrelation = samples.map { it.crossCorrelation },
		minRes = pypValues.ctfMinResOrThrow
	)
}

fun Document.readAVGROT() =
	AVGROT(getListOfDocuments("samples")?.map { it.readAVGROTSample() } ?: emptyList())

fun AVGROT.toDoc() = Document().apply {
	set("samples", samples.map { it.toDoc() })
}

fun Document.readAVGROTSample() =
	AVGROT.Sample(
		spatialFreq = getDouble("freq"),
		avgRotNoAstig = getDouble("avgrot_noastig"),
		avgRot = getDouble("avgrot"),
		ctfFit = getDouble("ctf_fit"),
		crossCorrelation = getDouble("quality_fit"),
		twoSigma = getDouble("noise")
	)

fun AVGROT.Sample.toDoc() = Document().apply {
	set("freq", spatialFreq)
	set("avgrot_noastig", avgRotNoAstig)
	set("avgrot", avgRot)
	set("ctf_fit", ctfFit)
	set("quality_fit", crossCorrelation)
	set("noise", twoSigma)
}
