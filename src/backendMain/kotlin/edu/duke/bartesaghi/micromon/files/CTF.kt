package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ArrayNode
import edu.duke.bartesaghi.micromon.getDoubleOrThrow
import edu.duke.bartesaghi.micromon.pyp.ImageDims
import org.bson.Document


/**
 * Coherence transfer function?
 */
data class CTF(
	val meanDefocus: Double,
	val cc: Double,
	val defocus1: Double,
	val defocus2: Double,
	val angast: Double,
	val ccc: Double,
	/** width of the source image, in unbinned pixels */
	val x: Double,
	/** height of the source image, in unbinned pixels */
	val y: Double,
	/** depth of the source image, in unbinned pixels */
	val z: Double,
	/** size of a single source image pixel, in angstroms */
	val pixelSize: Double,
	val voltage: Double,
	/** the binning factor, same in x, y, and z */
	val binningFactor: Double,
	val cccc: Double,
	val counts: Double
) {

	companion object {

		fun from(text: String): CTF {

			// try to parse the CTF file
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
				throw RuntimeException("can't parse CTF file:\n$text", t)
			}

			return CTF(
				meanDefocus = numbers[0],
				cc = numbers[1],
				defocus1 = numbers[2],
				defocus2 = numbers[3],
				angast = numbers[4],
				ccc = numbers[5],
				x = numbers[6],
				y = numbers[7],
				z = numbers[8],
				pixelSize = numbers[9],
				voltage = numbers[10],
				binningFactor = numbers[11],
				// apparently, sometimes these last two aren't in the CTF files at all
				cccc = if (numbers.size > 12) numbers[12] else 0.0,
				counts = if (numbers.size > 13) numbers[13] else 0.0
			)
		}

		fun from(json: ArrayNode) =
			CTF(
				meanDefocus = json.getDoubleOrThrow(0, "CTF.meanDefocus"),
				cc = json.getDoubleOrThrow(1, "CTF.cc"),
				defocus1 = json.getDoubleOrThrow(2, "CTF.defocus1"),
				defocus2 = json.getDoubleOrThrow(3, "CTF.defocus2"),
				angast = json.getDoubleOrThrow(4, "CTF.angast"),
				ccc = json.getDoubleOrThrow(5, "CTF.ccc"),
				x = json.getDoubleOrThrow(6, "CTF.x"),
				y = json.getDoubleOrThrow(7, "CTF.y"),
				z = json.getDoubleOrThrow(8, "CTF.z"),
				pixelSize = json.getDoubleOrThrow(9, "CTF.pixelSize"),
				voltage = json.getDoubleOrThrow(10, "CTF.voltage"),
				binningFactor = json.getDoubleOrThrow(11, "CTF.binningFactor"),
				cccc = if (json.size() > 12) json.getDoubleOrThrow(12, "CTF.cccc") else 0.0,
				counts = if (json.size() > 13) json.getDoubleOrThrow(13, "CTF.counts") else 0.0
			)

		fun empty() =
			CTF(
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0,
				0.0
			)
	}

	/**
	 * The dimensions of the source image, ie before binning
	 */
	fun sourceImageDims(): ImageDims =
		ImageDims(
			x.toInt(),
			y.toInt(),
			z.toInt(),
			binningFactor.toInt()
		)
}

fun Document.readCTF() =
	CTF(
		meanDefocus = getDouble("mean_df"),
		cc = getDouble("cc"),
		defocus1 = getDouble("df1"),
		defocus2 = getDouble("df2"),
		angast = getDouble("angast"),
		ccc = getDouble("ccc"),
		x = getDouble("x"),
		y = getDouble("y"),
		z = getDouble("z"),
		pixelSize = getDouble("pixel_size"),
		voltage = getDouble("voltage"),
		binningFactor = getDouble("binning_factor") ?: 1.0,
		cccc = getDouble("cccc"),
		counts = getDouble("counts")
	)

fun CTF.toDoc() = Document().apply {
	set("mean_df", meanDefocus)
	set("cc", cc)
	set("df1", defocus1)
	set("df2", defocus2)
	set("angast", angast)
	set("ccc", ccc)
	set("x", x)
	set("y", y)
	set("z", z)
	set("pixel_size", pixelSize)
	set("voltage", voltage)
	set("binning_factor", binningFactor)
	set("cccc", cccc)
	set("counts", counts)
}
