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
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.div
import kotlin.math.ceil
import kotlin.math.max


/**
 * Reads micrograph metadata written to the database by PYP
 */
/* NOTE: Micrograph objects are on the hot path
	We need to handle 10s of thousands of them efficiently,
	so try to keep things simple here and don't hold large object graphs in memory
*/
class Micrograph(doc: Document) {

	val jobId = doc.getString("jobId") ?: throw NoSuchElementException("micrograph document has no job id")
	val micrographId = doc.getString("micrographId") ?: throw NoSuchElementException("micrograph document has no micrograph id")

	companion object {

		fun get(jobId: String, micrographId: String): Micrograph? =
			Database.micrographs.get(jobId, micrographId)
				?.let { Micrograph(it) }

		fun <R> getAll(jobId: String, block: (Sequence<Micrograph>) -> R): R =
			Database.micrographs.getAll(jobId) {
				block(it.map { Micrograph(it) })
			}

		fun pypOutputImage(dir: Path, micrographId: String): Path =
			dir / "webp" / "$micrographId.webp"

		fun pypOutputImage(job: Job, micrographId: String): Path =
			pypOutputImage(job.dir,  micrographId)
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
		Database.micrographsAvgRot.get(jobId, micrographId)
			?: AVGROT()

	private fun getLogs(dir: Path): List<LogInfo> =
		dir.resolve("log")
			.listFiles()
			.filter {
				val filename = it.fileName.toString()
				filename.startsWith(micrographId) && filename.endsWith(".log")
			}
			.map { LogInfo(micrographId, it) }

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

		val rawPath = pypOutputImage(dir, micrographId)
		val cacheInfo = ImageCacheInfo(wwwDir, "micrograph-box.$micrographId")

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
	 * Returns a WebP image of the 2D CTF image
	 */
	private fun readCtffindImage(dir: Path, wwwDir: Path, size: ImageSize): ByteArray {

		val srcPath = dir / "webp" / "${micrographId}_ctffit.webp"
		val cacheInfo = ImageCacheInfo(wwwDir, "micrograph-ctffit.$micrographId")

		return size.readResize(srcPath, ImageType.Webp, cacheInfo)
			// no image, return a placeholder
			?: Resources.placeholderJpg(size)
	}

	fun readCtffindImage(job: Job, size: ImageSize): ByteArray =
		readCtffindImage(job.dir, job.wwwDir, size)

	fun readCtffindImage(session: Session, size: ImageSize): ByteArray =
		readCtffindImage(session.pypDir(session.newestArgs().pypNamesOrThrow()), session.wwwDir, size)

	fun getMetadata() =
		MicrographMetadata(
			micrographId,
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

	fun isInRanges(filter: PreprocessingFilter): Boolean =
		filter.ranges.all { range ->
			propDouble(MicrographProp[range.propId]) in range.min .. range.max
		}
}


fun Micrograph.propDouble(prop: MicrographProp): Double =
	when (prop) {
		MicrographProp.Time -> timestamp.toEpochMilli().toDouble()
		MicrographProp.CCC -> ctf.ccc
		MicrographProp.CCCC -> ctf.cccc
		MicrographProp.Defocus1 -> ctf.defocus1
		MicrographProp.Defocus2 -> ctf.defocus2
		MicrographProp.AngleAstig -> ctf.angast
		MicrographProp.AverageMotion -> xf.averageMotion()
		MicrographProp.NumParticles -> particleCount.toDouble()
	}
