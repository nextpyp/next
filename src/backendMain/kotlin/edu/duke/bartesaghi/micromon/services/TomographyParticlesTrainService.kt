package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.jobs.*
import edu.duke.bartesaghi.micromon.mongo.authProjectOrThrow
import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesTrainNodeConfig
import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlin.io.path.div


actual class TomographyParticlesTrainService : ITomographyParticlesTrainService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/node/${TomographyParticlesTrainNodeConfig.ID}/{jobId}") {

				fun PipelineContext<Unit, ApplicationCall>.authJob(permission: ProjectPermission): AuthInfo<Job> {
					val jobId = call.parameters.getOrFail("jobId")
					return call.authJob(jobId, permission)
				}

				get("results") {
					call.respondExceptions {

						// parse args
						val job = authJob(ProjectPermission.Read).job

						// serve the image
						val imagePath = job.dir / "train" / "training_loss.svgz"
						ImageType.Svgz.respond(call, imagePath)
							?.respondPlaceholder(call)
					}
				}
			}
		}
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
		authJob(this, permission)

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

	override suspend fun getArgs(): String = sanitizeExceptions {
		return TomographyParticlesTrainJob.args().toJson()
	}
}
