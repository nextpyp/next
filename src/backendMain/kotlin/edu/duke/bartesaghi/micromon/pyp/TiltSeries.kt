package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.files.*
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.sessions.Session
import edu.duke.bartesaghi.micromon.sessions.pypNamesOrThrow
import org.bson.Document
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.div


class TiltSeries(private val doc: Document) {

	val jobId: String by lazy { doc.getString("jobId") ?: throw NoSuchElementException("tilt series document has no job id") }
	val tiltSeriesId: String by lazy { doc.getString("tiltSeriesId") ?: throw NoSuchElementException("tilt series document has no tilt series id") }

	companion object {

		fun get(jobId: String, tiltSeries: String): TiltSeries? =
			Database.tiltSeries.get(jobId, tiltSeries)
				?.let { TiltSeries(it) }

		fun <R> getAll(jobId: String, block: (Sequence<TiltSeries>) -> R): R =
			Database.tiltSeries.getAll(jobId) {
				block(it.map { TiltSeries(it) })
			}

		fun pypOutputImage(dir: Path, tiltSeriesId: String): Path =
			dir / "webp" / "$tiltSeriesId.webp"

		fun pypOutputImage(job: Job, tiltSeriesId: String): Path =
			pypOutputImage(job.dir,  tiltSeriesId)
	}

	val timestamp: Instant =
		Instant.ofEpochMilli(doc.getLong("timestamp"))

	val particleCount: Int =
		doc.getInteger("particleCount")
			?: 0

	val ctf: CTF =
		doc.getDocument("ctf")
			?.readCTF()
			?: CTF.empty()

	val xf: XF =
		doc.getDocument("xf")
			?.readXF()
			?: XF()

	// NOTE: resist the urge to store more data in this db document, especially if it's a lot of data
	// the top-level micrograph data needs to stay small so we can load tens of thousands of them quickly
	// for extra data storage, make a new collection, eg, BOXX and AVGROT

	fun getAvgRot(): AVGROT =
		Database.tiltSeriesAvgRot.get(jobId, tiltSeriesId)
			?: AVGROT()

	fun getDriftMetadata(): DMD =
		Database.tiltSeriesDriftMetadata.get(jobId, tiltSeriesId)
			?: DMD.empty()

	private fun getLogs(dir: Path): List<LogInfo> =
		dir.resolve("log")
			.listFiles()
			.filter {
				val filename = it.fileName.toString()
				filename.startsWith(tiltSeriesId) && filename.endsWith(".log")
			}
			.map { LogInfo(tiltSeriesId, it) }

	fun getLogs(job: Job) =
		getLogs(job.dir)

	fun getLogs(session: Session) =
		getLogs(session.pypDir(session.newestArgs().pypNamesOrThrow()))


	/**
	 * Returns a WebP image that roughly matches the desired size.
	 *
	 * Resized WebP images are created from the raw pyp output image.
	 */
	private fun readImage(dir: Path, wwwDir: Path, size: ImageSize): ByteArray {

		val rawPath = pypOutputImage(dir, tiltSeriesId)
		val cacheInfo = ImageCacheInfo(wwwDir, "tilt-series-box.$tiltSeriesId")

		return size.readResize(rawPath, ImageType.Webp, cacheInfo)
			// no image, return a placeholder
			?: Resources.placeholderJpg(size)
			// TODO: we should actually return a WebP placeholder image here, not a JPG,
			//   but most browsers seem to render the placeholder image correctly anyway
	}

	fun readImage(job: Job, size: ImageSize): ByteArray =
		readImage(job.dir, job.wwwDir, size)

	fun readImage(session: Session, size: ImageSize): ByteArray =
		readImage(session.pypDir(session.newestArgs().pypNamesOrThrow()), session.wwwDir, size)


	/**
	 * Returns a WebP image representative of the 2D CTF fit.
	 *
	 * The raw 2D CTF image is a montage of the whole tilt series,
	 * so this function returns a central tile from the montage.
	 */
	private fun readCtffindImage(dir: Path, wwwDir: Path, size: ImageSize): ByteArray {

		val srcPath = dir / "webp" / "${tiltSeriesId}_2D_ctftilt.webp"
		val cacheInfo = ImageCacheInfo(wwwDir, "tiltseries-2Dctffit.cropped.2.$tiltSeriesId")

		val tiler = { image: BufferedImage ->

			// get the montage sizes
			val numTilts = Database.tiltSeriesDriftMetadata.countTilts(jobId, tiltSeriesId).toInt()
			val montage = MontageSizes.fromSquaredMontageImage(image.width, image.height, numTilts)

			// crop the image to the center tile
			val centerTile = numTilts/2
			val xIndex = centerTile % montage.tilesY
			val yIndex = centerTile / montage.tilesY
			image.getSubimage(
				xIndex*montage.tileWidth,
				yIndex*montage.tileHeight,
				montage.tileWidth,
				montage.tileHeight
			)
		}

		return size.readResize(srcPath, ImageType.Webp, cacheInfo, tiler)
			// no image, return a placeholder
			?: Resources.placeholderJpg(size)
	}

	fun readCtffindImage(job: Job, size: ImageSize): ByteArray =
		readCtffindImage(job.dir, job.wwwDir, size)

	fun readCtffindImage(session: Session, size: ImageSize): ByteArray =
		readCtffindImage(session.pypDir(session.newestArgs().pypNamesOrThrow()), session.wwwDir, size)

	fun getMetadata() =
		TiltSeriesData(
			tiltSeriesId,
			timestamp.toEpochMilli(),
			ctf.ccc,
			ctf.cccc,
			ctf.defocus1,
			ctf.defocus2,
			ctf.angast,
			xf.averageMotion(),
			particleCount,
			ctf.sourceImageDims()
		)

	fun getDriftMetadata(pypValues: ArgValues) =
		getDriftMetadata().let { dmd ->
			DriftMetadata(
				tilts = dmd.tilts,
				drifts = dmd.drifts.map {
					it.map { innerIt ->
						DriftXY(innerIt.x, innerIt.y)
					}
				},
				ctfValues = dmd.ctfValues.map {
					CtfTiltValues(
						index = it.index,
						defocus1 = it.defocus1,
						defocus2 = it.defocus2,
						astigmatism = it.astigmatism,
						cc = it.cc,
						resolution = it.resolution
					)
				},
				ctfProfiles = dmd.ctfProfiles.map {
					it.data(pypValues)
				},
				tiltAxisAngle = dmd.tiltAxisAngle
			)
		}

	fun getAlignedTiltSeriesMontage(dir: Path):        ByteArray = dir.resolve("webp").resolve("${tiltSeriesId}_ali.webp").readBytes()
	fun getReconstructionTiltSeriesMontage(dir: Path): ByteArray = dir.resolve("webp").resolve("${tiltSeriesId}_rec.webp").readBytes()
	fun get2dCtfTiltMontage(dir: Path):                ByteArray = dir.resolve("webp" ).resolve("${tiltSeriesId}_2D_ctftilt.webp").readBytes()
	fun getRawTiltSeriesMontage(dir: Path):            ByteArray = dir.resolve("webp").resolve("${tiltSeriesId}_raw.webp").readBytes()
	fun getSidesTiltSeriesImage(dir: Path):            ByteArray = dir.resolve("webp").resolve("${tiltSeriesId}_sides.webp").readBytes()

	fun readVirionThresholdsImage(job: Job, virionId: Int): ByteArray? {

		val virionIndex = virionId - 1

		// eg, tomo/tilt_series_vir0000_binned_nad.png
		val path = job.dir / "webp" / "${tiltSeriesId}_vir${"%04d".format(virionIndex)}_binned_nad.webp"

		return path
			.takeIf { it.exists() }
			?.readBytes()
	}

	fun isInRanges(filter: PreprocessingFilter): Boolean =
		filter.ranges.all { range ->
			propDouble(TiltSeriesProp[range.propId]) in range.min .. range.max
		}
}


fun TiltSeries.propDouble(prop: TiltSeriesProp): Double =
	when (prop) {
		TiltSeriesProp.Time -> timestamp.toEpochMilli().toDouble()
		TiltSeriesProp.CCC -> ctf.ccc
		TiltSeriesProp.CCCC -> ctf.cccc
		TiltSeriesProp.Defocus1 -> ctf.defocus1
		TiltSeriesProp.Defocus2 -> ctf.defocus2
		TiltSeriesProp.AngleAstig -> ctf.angast
		TiltSeriesProp.AverageMotion -> xf.averageMotion()
		TiltSeriesProp.NumParticles -> particleCount.toDouble()
	}
