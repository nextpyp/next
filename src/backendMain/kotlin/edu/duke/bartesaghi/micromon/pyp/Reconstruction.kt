package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.exists
import edu.duke.bartesaghi.micromon.files.*
import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.mongo.getListOfListsOfDoubles
import edu.duke.bartesaghi.micromon.projects.RepresentativeImage
import edu.duke.bartesaghi.micromon.readString
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document
import java.time.Instant


class Reconstruction(doc: Document) {

    companion object {

        fun get(jobId: String, reconstructionId: String): Reconstruction? =
            Database.instance.reconstructions.get(jobId, reconstructionId)
                ?.let { Reconstruction(it) }

        fun <R> getAll(jobId: String, block: (Sequence<Reconstruction>) -> R): R =
            Database.instance.reconstructions.getAll(jobId) {
                block(it.map { innerIt -> Reconstruction(innerIt) })
            }

		fun filenameFragment(job: Job, classNum: Int, iteration: Int? = null): String {
			val suffix = iteration
				?.let {"_${"%02d".format(iteration)}" }
				?: ""
			return "${job.baseConfig.id}-${job.idOrThrow}_r${"%02d".format(classNum)}$suffix"
		}

		fun representativeImageUrl(jobId: String, repImage: RepresentativeImage): String? {
			return IntegratedRefinementService.mapImageUrl(
				jobId,
				classNum = repImage.params?.getInteger("classNum") ?: return null,
				iteration = repImage.params.getInteger("iteration") ?: return null,
				ImageSize.Small
			)
		}
    }

	val jobId: String = doc.getString("jobId") ?: throw NoSuchElementException("reconstruction document has no job id")
	val reconstructionId: String = doc.getString("reconstructionId") ?: throw NoSuchElementException("reconstruction document has no reconstruction id")
	val timestamp: Instant = Instant.ofEpochMilli(doc.getLong("timestamp"))
    val classNum: Int = doc.getInteger("classNum") ?: throw NoSuchElementException("reconstruction document has no class number")
    val iteration: Int = doc.getInteger("iteration") ?: throw NoSuchElementException("reconstruction document has no iteration number")

    // USE THE BELOW FOR DEBUGGING IF YOU ARE UNABLE TO READ FROM THE DATABASE
//    val metadata = RMD(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
//    val fsc = listOf(listOf(1.0))
//    val plots = RPD(listOf(listOf(1.0)), listOf(listOf(1.0)),
//        RPD.HistogramData(listOf(1.0), listOf(1.0)),
//        RPD.HistogramData(listOf(1.0), listOf(1.0)),
//        RPD.HistogramData(listOf(1.0), listOf(1.0)),
//        RPD.HistogramData(listOf(1.0), listOf(1.0)),
//        RPD.HistogramData(listOf(1.0), listOf(1.0)),
//        RPD.HistogramData(listOf(1.0), listOf(1.0)),
//        emptyList()
//    )

    val metadata by lazy { doc.getDocument("metadata").readReconstructionMetadata() }
    val fsc by lazy { doc.getListOfListsOfDoubles("fsc") }
    val plots by lazy { doc.getDocument("plots").readReconstructionPlotData() }

	fun toData() = ReconstructionData(
        reconstructionId,
        timestamp.toEpochMilli(),
        classNum,
        iteration
    )

	fun toPlotsData() = ReconstructionPlotsData(
		plots,
		metadata,
		fsc
	)

	fun filenameFragment(job: Job): String =
		filenameFragment(job, classNum, iteration)

    fun getLog(job: Job): String {
        val dir = job.dir.resolve("frealign").resolve("log")
        val reconstructionString = filenameFragment(job)
        val searchLog = dir.resolve("${reconstructionString}_msearch.log")
			.takeIf { it.exists() }
			?.readString()
			?: ""
        val reconstLog = dir.resolve("${reconstructionString}_mreconst.log")
			.takeIf { it.exists() }
			?.readString()
			?: ""
        return searchLog + "\n" + reconstLog
    }

	fun representativeImage(): RepresentativeImage =
		RepresentativeImage(Document().apply {
			this["classNum"] = classNum
			this["iteration"] = iteration
		})
}
