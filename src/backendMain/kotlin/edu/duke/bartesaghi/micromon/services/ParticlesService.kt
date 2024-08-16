package edu.duke.bartesaghi.micromon.services

import com.google.inject.Inject
import edu.duke.bartesaghi.micromon.auth.authOrThrow
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.mongo.authJobOrThrow
import edu.duke.bartesaghi.micromon.parseOwnerType
import edu.duke.bartesaghi.micromon.respondExceptions
import edu.duke.bartesaghi.micromon.sanitizeExceptions
import edu.duke.bartesaghi.micromon.sessions.authSessionOrThrow
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.kvision.remote.RemoteOption
import io.kvision.remote.ServiceException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


actual class ParticlesService : IParticlesService, Service {

	companion object {

		fun init(routing: Routing) {

			routing.route("kv/particles/{ownerType}/{ownerId}/{datumId}/") {

				fun PipelineContext<Unit,ApplicationCall>.parseOwnerId(): String =
					call.parameters.getOrFail("ownerId")

				fun PipelineContext<Unit,ApplicationCall>.parseDatumId(): String =
					call.parameters.getOrFail("datumId")


				post("getParticles2D") {
					call.respondExceptions {

						val ownerType = parseOwnerType()
						val ownerId = parseOwnerId()
						val datumId = parseDatumId()
						val name = call.receiveText()

						// NOTE: this needs to handle 100s of particles efficiently
						// this needs to stay simple and optimized!!
						val json = service.getParticles2D(ownerType, ownerId, name, datumId)
							.let { Json.encodeToString(it) }

						call.respondText(json, ContentType.Application.Json)
					}
				}

				post("getParticles3D") {
					call.respondExceptions {

						val ownerType = parseOwnerType()
						val ownerId = parseOwnerId()
						val datumId = parseDatumId()
						val name = call.receiveText()

						// NOTE: this needs to handle 100s of particles efficiently
						// this needs to stay simple and optimized!!
						val json = service.getParticles3D(ownerType, ownerId, name, datumId)
							.let { Json.encodeToString(it) }

						call.respondText(json, ContentType.Application.Json)
					}
				}
			}
		}

		private val PipelineContext<Unit, ApplicationCall>.service get() =
			getService<ParticlesService>()
	}


	@Inject
	override lateinit var call: ApplicationCall

	private fun authRead(ownerType: OwnerType, ownerId: String) {
		val user = call.authOrThrow()
		when (ownerType) {
			OwnerType.Project -> user.authJobOrThrow(ProjectPermission.Read, ownerId)
			OwnerType.Session -> user.authSessionOrThrow(ownerId, SessionPermission.Read)
		}
	}

	private fun authWrite(ownerType: OwnerType, ownerId: String) {
		val user = call.authOrThrow()
		when (ownerType) {
			OwnerType.Project -> user.authJobOrThrow(ProjectPermission.Write, ownerId)
			OwnerType.Session -> user.authSessionOrThrow(ownerId, SessionPermission.Write)
		}
	}


	override suspend fun getLists(ownerType: OwnerType, ownerId: String): List<ParticlesList> = sanitizeExceptions {
		authRead(ownerType, ownerId)
		Database.particleLists.getAll(ownerId)
	}

	override suspend fun getListOptions(search: String?, initial: String?, state: String?): List<RemoteOption> = sanitizeExceptions {

		// state should be $ownerType/$ownerId
		val (ownerType, ownerId) = state
			?.let { s ->
				s
					.split("/")
					.takeIf { it.size == 2 }
					?.let l@{ parts ->
						val ownerType = OwnerType[parts[0]]
							?: return@l null
						val ownerId = parts[1]
						ownerType to ownerId
					}
			}
			?: throw IllegalArgumentException("bad state")

		authRead(ownerType, ownerId)

		val header = listOf(
			RemoteOption(NoneFilterOption, "(None)"),
			RemoteOption(divider = true)
		)

		val options = Database.particleLists.getAll(ownerId)
			.map { RemoteOption(it.name) }

		return header + options
	}

	override suspend fun getList(ownerType: OwnerType, ownerId: String, name: String): Option<ParticlesList> = sanitizeExceptions {
		authRead(ownerType, ownerId)
		Database.particleLists.get(ownerId, name)
			.toOption()
	}

	override suspend fun addList(ownerType: OwnerType, ownerId: String, name: String, type: ParticlesType): Option<ParticlesList> = sanitizeExceptions {
		authWrite(ownerType, ownerId)
		val list = ParticlesList(
			ownerId = ownerId,
			name = name,
			type = type,
			source = ParticlesSource.User
		)
		if (Database.particleLists.createIfNeeded(list)) {
			list
		} else {
			null
		}.toOption()
	}

	override suspend fun deleteList(ownerType: OwnerType, ownerId: String, name: String): Boolean = sanitizeExceptions {
		authWrite(ownerType, ownerId)

		// can only delete user-created lists here
		Database.particleLists.get(ownerId, name)
			?.takeIf { it.source == ParticlesSource.User }
			?: return false

		Database.particleLists.delete(ownerId, name)
		Database.particles.deleteAllParticles(ownerId, name)
		true
	}

	override suspend fun copyList(ownerType: OwnerType, ownerId: String, name: String, newName: String): Option<ParticlesList> = sanitizeExceptions {
		authWrite(ownerType, ownerId)

		val list = Database.particleLists.get(ownerId, name)
			?: throw ServiceException("list not found: $name")

		// copy the list
		val newList = list.copy(
			name = newName,
			source = ParticlesSource.User
		)
		if (!Database.particleLists.createIfNeeded(newList)) {
			return null.toOption()
		}

		// copy the particles
		Database.particles.copyAllParticles(ownerId, name, newName)

		newList.toOption()
	}

	fun getParticles2D(ownerType: OwnerType, ownerId: String, name: String, datumId: String): Particles2DData = sanitizeExceptions {
		authRead(ownerType, ownerId)
		Database.particles.getParticles2D(ownerId, name, datumId)
			.toData()
	}

	override suspend fun addParticle2D(ownerType: OwnerType, ownerId: String, name: String, datumId: String, particle: Particle2D): Int = sanitizeExceptions {
		authWrite(ownerType, ownerId)

		// only add particles to user-sourced lists
		Database.particleLists.get(ownerId, name)
			?.takeIf { it.source == ParticlesSource.User }
			?: throw ServiceException("list is not user-writable")

		Database.particles.addParticle2D(ownerId, name, datumId, particle)
	}

	fun getParticles3D(ownerType: OwnerType, ownerId: String, name: String, datumId: String): Particles3DData = sanitizeExceptions {
		authRead(ownerType, ownerId)
		Database.particles.getParticles3D(ownerId, name, datumId)
			.toData()
	}

	override suspend fun addParticle3D(ownerType: OwnerType, ownerId: String, name: String, datumId: String, particle: Particle3D): Int = sanitizeExceptions {
		authWrite(ownerType, ownerId)

		// only add particles to user-sourced lists
		Database.particleLists.get(ownerId, name)
			?.takeIf { it.source == ParticlesSource.User }
			?: throw ServiceException("list is not user-writable")

		Database.particles.addParticle3D(ownerId, name, datumId, particle)
	}

	override suspend fun countParticles(ownerType: OwnerType, ownerId: String, name: String, datumId: String?): Option<Long> = sanitizeExceptions {
		authRead(ownerType, ownerId)
		if (datumId != null) {
			Database.particles.countParticles(ownerId, name, datumId)
		} else {
			Database.particles.countAllParticles(ownerId, name)
		}.toOption()
	}

	override suspend fun deleteParticle(ownerType: OwnerType, ownerId: String, name: String, datumId: String, particleId: Int) = sanitizeExceptions {
		authWrite(ownerType, ownerId)

		// only add particles to user-sourced lists
		Database.particleLists.get(ownerId, name)
			?.takeIf { it.source == ParticlesSource.User }
			?: throw ServiceException("list is not user-writable")

		Database.particles.deleteParticle(ownerId, name, datumId, particleId)
	}

	override suspend fun getVirionThresholds(ownerType: OwnerType, ownerId: String, name: String, datumId: String): VirionThresholdData = sanitizeExceptions {
		authRead(ownerType, ownerId)
		Database.particles.getThresholds(ownerId, name, datumId)
			.toVirionThresholdData()
	}

	override suspend fun setVirionThreshold(ownerType: OwnerType, ownerId: String, name: String, datumId: String, virionId: Int, threshold: Int?) = sanitizeExceptions {
		authWrite(ownerType, ownerId)
		Database.particles.setThreshold(ownerId, name, datumId, virionId, threshold)
	}
}
