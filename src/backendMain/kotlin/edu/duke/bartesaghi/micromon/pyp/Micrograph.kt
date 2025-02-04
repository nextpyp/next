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
			Database.instance.micrographs.get(jobId, micrographId)
				?.let { Micrograph(it) }

		fun <R> getAll(jobId: String, block: (Sequence<Micrograph>) -> R): R =
			Database.instance.micrographs.getAll(jobId) {
				block(it.map { Micrograph(it) })
			}

		suspend fun <R> getAllAsync(jobId: String, block: suspend (Sequence<Micrograph>) -> R): R =
			Database.instance.micrographs.getAllAsync(jobId) {
				block(it.map { Micrograph(it) })
			}

		fun outputImagePath(dir: Path, micrographId: String): Path =
			dir / "webp" / "$micrographId.webp"

		fun ctffitImagePath(dir: Path, micrographId: String): Path =
			dir / "webp" / "${micrographId}_ctffit.webp"
	}

	val timestamp: Instant =
		Instant.ofEpochMilli(doc.getLong("timestamp"))

	val autoParticleCount: Int =
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
		Database.instance.micrographsAvgRot.get(jobId, micrographId)
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
			autoParticleCount,
			ctf.imageDims()
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
		MicrographProp.NumParticles -> autoParticleCount.toDouble()
	}
