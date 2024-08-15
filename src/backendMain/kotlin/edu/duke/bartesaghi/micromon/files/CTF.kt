package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ArrayNode
import edu.duke.bartesaghi.micromon.getDoubleOrThrow
import edu.duke.bartesaghi.micromon.mongo.getNumberAsIntOrThrow
import edu.duke.bartesaghi.micromon.pyp.ImageDims
import edu.duke.bartesaghi.micromon.pyp.ValueA
import edu.duke.bartesaghi.micromon.pyp.ValueUnbinnedI
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
	val x: ValueUnbinnedI,
	/** height of the source image, in unbinned pixels */
	val y: ValueUnbinnedI,
	/** depth of the source image, in unbinned pixels */
	val z: ValueUnbinnedI,
	/** size of a single source image pixel, in angstroms */
	val pixelSize: ValueA,
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
				x = ValueUnbinnedI(numbers[6].toInt()),
				y = ValueUnbinnedI(numbers[7].toInt()),
				z = ValueUnbinnedI(numbers[8].toInt()),
				pixelSize = ValueA(numbers[9]),
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
				x = ValueUnbinnedI(json.getDoubleOrThrow(6, "CTF.x").toInt()),
				y = ValueUnbinnedI(json.getDoubleOrThrow(7, "CTF.y").toInt()),
				z = ValueUnbinnedI(json.getDoubleOrThrow(8, "CTF.z").toInt()),
				pixelSize = ValueA(json.getDoubleOrThrow(9, "CTF.pixelSize")),
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
				ValueUnbinnedI(0),
				ValueUnbinnedI(0),
				ValueUnbinnedI(0),
				ValueA(0.0),
				0.0,
				0.0,
				0.0,
				0.0
			)
	}

	/**
	 * The dimensions of the source image
	 */
	fun imageDims(): ImageDims =
		ImageDims(
			x,
			y,
			z,
			binningFactor.toInt(),
			pixelSize
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
		x = ValueUnbinnedI(getNumberAsIntOrThrow("x")),
		y = ValueUnbinnedI(getNumberAsIntOrThrow("y")),
		z = ValueUnbinnedI(getNumberAsIntOrThrow("z")),
		pixelSize = ValueA(getDouble("pixel_size")),
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
	set("x", x.v)
	set("y", y.v)
	set("z", z.v)
	set("pixel_size", pixelSize.v)
	set("voltage", voltage)
	set("binning_factor", binningFactor)
	set("cccc", cccc)
	set("counts", counts)
}
