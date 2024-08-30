package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.TomographyDenoisingTrainingNodeConfig
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import java.io.FileNotFoundException
import kotlin.io.path.div


actual class TomographyDenoisingTrainingService : ITomographyDenoisingTrainingService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/node/${TomographyDenoisingTrainingNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.parseJobId(): String =
					call.parameters.getOrFail("jobId")

				get("train_results") {
					call.respondExceptions {

						val jobId = parseJobId()

						val bytes = try {
							service.getTrainResults(jobId)
						} catch (ex: FileNotFoundException) {
							Resources.placeholderSvgz()
						}

						call.response.headers.append(HttpHeaders.ContentEncoding, "gzip")
						call.respondBytes(bytes, ContentType.Image.Svgz)
					}
				}
			}
		}

		private val PipelineContext<Unit, ApplicationCall>.service get() =
			getService<TomographyDenoisingTrainingService>()
	}


	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inTomograms: CommonJobData.DataId, args: TomographyDenoisingTrainingArgs): TomographyDenoisingTrainingData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyDenoisingTrainingJob(userId, projectId)
		job.args.next = args
		job.inTomograms = inTomograms
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyDenoisingTrainingJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: TomographyDenoisingTrainingArgs?): TomographyDenoisingTrainingData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyDenoisingTrainingData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyDenoisingTrainingJob.args().toJson()
	}

	suspend fun getTrainResults(jobId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		// TODO: should this be method-specific?
		val path = job.dir / "train" / "training_loss.svgz"
		return path.readBytes()
	}
}
