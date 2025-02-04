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
			Database.instance.tiltSeries.get(jobId, tiltSeries)
				?.let { TiltSeries(it) }

		fun <R> getAll(jobId: String, block: (Sequence<TiltSeries>) -> R): R =
			Database.instance.tiltSeries.getAll(jobId) {
				block(it.map { TiltSeries(it) })
			}

		suspend fun <R> getAllAsync(jobId: String, block: suspend (Sequence<TiltSeries>) -> R): R =
			Database.instance.tiltSeries.getAllAsync(jobId) {
				block(it.map { TiltSeries(it) })
			}

		fun outputImagePath(dir: Path, tiltSeriesId: String): Path =
			dir / "webp" / "$tiltSeriesId.webp"

		fun recPath(dir: Path, tiltSeriesId: String): Path =
			dir / "mrc" / "$tiltSeriesId.rec"

		fun metadataPath(dir: Path, tiltSeriesId: String): Path =
			dir / "frealign" / "artiax" / "${tiltSeriesId}_K1.star"

		fun alignedMontagePath(dir: Path, tiltSeriesId: String): Path =
			dir / "webp" / "${tiltSeriesId}_ali.webp"

		fun reconstructionTiltSeriesMontagePath(dir: Path, tiltSeriesId: String): Path {
			// try webp first, fall back to png otherwise (this is done to workaround the max size limitation of the webp format)
			val subdir = dir / "webp"
			val filename = "${tiltSeriesId}_rec"
			val webp = subdir / "$filename.webp"
			if (webp.exists()) {
				return webp
			}
			return subdir / "$filename.png"
		}

		fun twodCtfTiltMontagePath(dir: Path, tiltSeriesId: String): Path =
			dir / "webp" / "${tiltSeriesId}_2D_ctftilt.webp"

		fun rawTiltSeriesMontagePath(dir: Path, tiltSeriesId: String): Path =
			dir / "webp" / "${tiltSeriesId}_raw.webp"

		fun sidesImagePath(dir: Path, tiltSeriesId: String): Path =
			dir / "webp" / "${tiltSeriesId}_sides.webp"

		fun virionThresholdsImagePath(dir: Path, tiltSeriesId: String, virionId: Int): Path {
			val virionIndex = virionId - 1
			// eg, tomo/tilt_series_vir0000_binned_nad.png
			return dir / "webp" / "${tiltSeriesId}_vir${"%04d".format(virionIndex)}_binned_nad.webp"
		}

		fun montageCenterTiler(ownerId: String, tiltSeriesId: String): (BufferedImage) -> BufferedImage =
			{ image: BufferedImage ->

				// get the montage sizes
				val numTilts = Database.instance.tiltSeriesDriftMetadata.countTilts(ownerId, tiltSeriesId).toInt()
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
	}

	val timestamp: Instant =
		Instant.ofEpochMilli(doc.getLong("timestamp"))

	val autoParticleCount: Int =
		doc.getInteger("particleCount")
			?: 0

	val autoVirionCount: Int =
		doc.getInteger("virionCount")
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
		Database.instance.tiltSeriesAvgRot.get(jobId, tiltSeriesId)
			?: AVGROT()

	fun getDriftMetadata(): DMD =
		Database.instance.tiltSeriesDriftMetadata.get(jobId, tiltSeriesId)
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
			autoParticleCount,
			autoVirionCount,
			ctf.imageDims()
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
		TiltSeriesProp.NumParticles -> autoParticleCount.toDouble()
	}
