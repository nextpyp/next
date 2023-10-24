package edu.duke.bartesaghi.micromon.services

import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import io.kvision.remote.RemoteOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient


@KVService
interface IParticlesService {

	@KVBindingRoute("particles/getLists")
	suspend fun getLists(ownerType: OwnerType, ownerId: String): List<ParticlesList>

	@KVBindingRoute("particles/getListOptions")
	suspend fun getListOptions(search: String?, initial: String?, state: String?): List<RemoteOption>

	@KVBindingRoute("particles/getList")
	suspend fun getList(ownerType: OwnerType, ownerId: String, name: String): Option<ParticlesList>

	@KVBindingRoute("particles/addList")
	suspend fun addList(ownerType: OwnerType, ownerId: String, name: String, type: ParticlesType): Option<ParticlesList>

	@KVBindingRoute("particles/deleteList")
	suspend fun deleteList(ownerType: OwnerType, ownerId: String, name: String): Boolean

	@KVBindingRoute("particles/copyList")
	suspend fun copyList(ownerType: OwnerType, ownerId: String, name: String, newName: String): Option<ParticlesList>


	@KVBindingRoute("particles/addParticle2D")
	suspend fun addParticle2D(ownerType: OwnerType, ownerId: String, name: String, datumId: String, particle: Particle2D): Int

	@KVBindingRoute("particles/addParticle3D")
	suspend fun addParticle3D(ownerType: OwnerType, ownerId: String, name: String, datumId: String, particle: Particle3D): Int


	@KVBindingRoute("particles/countParticles")
	suspend fun countParticles(ownerType: OwnerType, ownerId: String, name: String, datumId: String?): Option<Long>

	@KVBindingRoute("particles/deleteParticle")
	suspend fun deleteParticle(ownerType: OwnerType, ownerId: String, name: String, datumId: String, particleId: Int)


	@KVBindingRoute("particles/getVirionThresholds")
	suspend fun getVirionThresholds(ownerType: OwnerType, ownerId: String, name: String, datumId: String): VirionThresholdData

	@KVBindingRoute("particles/setVirionThreshold")
	suspend fun setVirionThreshold(ownerType: OwnerType, ownerId: String, name: String, datumId: String, virionId: Int, threshold: Int?)
}


@Serializable
enum class OwnerType(val id: String) {

	Project("project"),
	Session("session");

	companion object {

		operator fun get(id: String): OwnerType? =
			values().find { it.id == id }

		fun getOrThrow(id: String): OwnerType =
			get(id) ?: throw NoSuchElementException("no particles type with id=$id")
	}
}


@Serializable
enum class ParticlesType(val id: String) {

	Particles2D("particles2D"),
	Particles3D("particles3D"),
	Virions3D("virions3D");

	companion object {

		operator fun get(id: String): ParticlesType? =
			values().find { it.id == id }

		fun getOrThrow(id: String): ParticlesType =
			get(id) ?: throw NoSuchElementException("no particles type with id=$id")
	}
}

@Serializable
enum class ParticlesSource(val id: String) {

	User("user"),
	Pyp("pyp");

	companion object {

		operator fun get(id: String): ParticlesSource? =
			values().find { it.id == id }

		fun getOrThrow(id: String): ParticlesSource =
			get(id) ?: throw NoSuchElementException("no particles source with id=$id")
	}
}

@Serializable
data class ParticlesList(
	val ownerId: String,
	val name: String,
	val type: ParticlesType,
	val source: ParticlesSource
) {

	companion object {

		const val PypAutoParticles = "Auto Particles"
		const val PypAutoVirions = "Auto Virions"

		fun autoParticles2D(ownerId: String): ParticlesList =
			ParticlesList(
				ownerId = ownerId,
				name = PypAutoParticles,
				type = ParticlesType.Particles2D,
				source = ParticlesSource.Pyp
			)

		fun autoParticles3D(ownerId: String): ParticlesList =
			ParticlesList(
				ownerId = ownerId,
				name = PypAutoParticles,
				type = ParticlesType.Particles3D,
				source = ParticlesSource.Pyp
			)

		fun autoVirions(ownerId: String): ParticlesList =
			ParticlesList(
				ownerId = ownerId,
				name = PypAutoVirions,
				type = ParticlesType.Virions3D,
				source = ParticlesSource.Pyp
			)
	}
}


@Serializable
data class Particle2D(
	val x: Double,
	val y: Double,
	val r: Double
) {

	companion object {
		// define the companion object here so we can extend it elsewhere
	}
}

@Serializable
data class Particle3D(
	val x: Double,
	val y: Double,
	val z: Double,
	val r: Double
) {

	companion object {
		// define the companion object here so we can extend it elsewhere
	}
}


class IndicesById(val ids: List<Int>) {

	private var map: Map<Int,Int>? = null

	private fun buildIfNeeded(): Map<Int,Int> {

		map?.let { return it }

		val map = ids
			.withIndex()
			.associate { (i, id) -> id to i }
		this.map = map
		return map
	}

	operator fun get(id: Int): Int? =
		buildIfNeeded()[id]
}


/**
 * A 2D particles format that's designed to get good performance with KVision's serializer
 */
@Serializable
data class Particles2DData(
	val ids: List<Int>,
	/** packed x, y, radius */
	val coords: List<Double>
) : Iterable<Pair<Int,Particle2D>> {

	@Transient
	private var indicesById = IndicesById(ids)

	private fun particle(i: Int): Particle2D {
		val i3 = i*3
		return Particle2D(
			x = coords[i3],
			y = coords[i3 + 1],
			r = coords[i3 + 2]
		)
	}

	operator fun get(id: Int): Particle2D? =
		indicesById[id]
			?.let { particle(it) }

	override fun iterator(): Iterator<Pair<Int,Particle2D>> =
		ids.indices
			.map { i -> ids[i] to particle(i) }
			.iterator()

	fun toMap(): Map<Int,Particle2D> =
		associate { (particleId, particle) -> particleId to particle }
}


fun Map<Int,Particle2D>.toData(): Particles2DData {

	val ids = ArrayList<Int>()
	val coords = ArrayList<Double>()

	for ((id, particle) in this) {
		ids.add(id)
		coords.add(particle.x)
		coords.add(particle.y)
		coords.add(particle.r)
	}

	return Particles2DData(
		ids = ids,
		coords = coords
	)
}


/**
 * A 3D particles format that's good for getting good performance with KVision's serializer
 */
@Serializable
data class Particles3DData(
	val ids: List<Int>,
	/** packed x, y, z, radius */
	val coords: List<Double>
) : Iterable<Pair<Int,Particle3D>> {

	@Transient
	private var indicesById = IndicesById(ids)

	private fun particle(i: Int): Particle3D {
		val i4 = i*4
		return Particle3D(
			x = coords[i4],
			y = coords[i4 + 1],
			z = coords[i4 + 2],
			r = coords[i4 + 3]
		)
	}

	operator fun get(id: Int): Particle3D? =
		indicesById[id]
			?.let { particle(it) }

	override fun iterator(): Iterator<Pair<Int,Particle3D>> =
		ids.indices
			.map { i -> ids[i] to particle(i) }
			.iterator()

	fun toMap(): Map<Int,Particle3D> =
		associate { (particleId, particle) -> particleId to particle }
}

fun Map<Int,Particle3D>.toData(): Particles3DData {

	val ids = ArrayList<Int>()
	val coords = ArrayList<Double>()

	for ((id, particle) in this) {
		ids.add(id)
		coords.add(particle.x)
		coords.add(particle.y)
		coords.add(particle.z)
		coords.add(particle.r)
	}

	return Particles3DData(
		ids = ids,
		coords = coords
	)
}


// NOTE: apparently KVision can't deserialize maps on the client side as a top-level function return
// it can't seem to handle a list of Pairs either
// so we need custom data types for these:
@Serializable
data class VirionThresholdData(
	val thresholdsByParticleId: Map<Int,Int>
)

fun Map<Int,Int>.toVirionThresholdData() =
	VirionThresholdData(this)
