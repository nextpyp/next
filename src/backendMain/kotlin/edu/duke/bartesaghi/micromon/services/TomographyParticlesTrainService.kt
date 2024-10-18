package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesTrainNodeConfig
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import java.io.FileNotFoundException
import kotlin.io.path.div


actual class TomographyParticlesTrainService : ITomographyParticlesTrainService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/node/${TomographyParticlesTrainNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.parseJobId(): String =
					call.parameters.getOrFail("jobId")

				get("results") {
					call.respondExceptions {

						// parse args
						val jobId = parseJobId()

						val bytes = try {
							service.getResults(jobId)
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
			getService<TomographyParticlesTrainService>()
	}

	
	@Inject
	override lateinit var call: ApplicationCall

	override suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyParticlesTrainArgs): TomographyParticlesTrainData = sanitizeExceptions {

		val user = call.authOrThrow()
		user.authProjectOrThrow(ProjectPermission.Write, userId, projectId)

		// make the job
		val job = TomographyParticlesTrainJob(userId, projectId)
		job.args.next = args
		job.inParticles = inData
		job.create()

		return job.data()
	}

	private fun String.authJob(permission: ProjectPermission): AuthInfo<TomographyParticlesTrainJob> =
		authJob(permission, this)

	override suspend fun edit(jobId: String, args: TomographyParticlesTrainArgs?): TomographyParticlesTrainData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Write).job

		// save the new args
		job.args.next = args
		job.update()

		return job.data()
	}

	override suspend fun get(jobId: String): TomographyParticlesTrainData = sanitizeExceptions {

		val job = jobId.authJob(ProjectPermission.Read).job

		return job.data()
	}

	override suspend fun getArgs(includeForwarded: Boolean): String = sanitizeExceptions {
		return TomographyParticlesTrainJob.args(includeForwarded).toJson()
	}

	fun getResults(jobId: String): ByteArray {
		val job = jobId.authJob(ProjectPermission.Read).job
		val path = job.dir / "train" / "training_loss.svgz"
		return path.readBytes()
	}

}
