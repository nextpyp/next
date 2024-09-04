package edu.duke.bartesaghi.micromon.mongo

import com.mongodb.client.model.*
import edu.duke.bartesaghi.micromon.pyp.ValueBinnedF
import edu.duke.bartesaghi.micromon.pyp.ValueBinnedI
import edu.duke.bartesaghi.micromon.pyp.ValueUnbinnedF
import edu.duke.bartesaghi.micromon.pyp.ValueUnbinnedI
import edu.duke.bartesaghi.micromon.services.*
import org.bson.Document


class ParticleLists {

	private val collection = Database.db.getCollection("particleLists")

	init {
		collection.createIndex(Document().apply {
			this["ownerId"] = 1
		})
	}

	private fun filter(ownerId: String, name: String) =
		Filters.eq("_id", "$ownerId/$name")

	private fun filterOwner(ownerId: String) =
		Filters.eq("ownerId", ownerId)

	fun get(ownerId: String, name: String): ParticlesList? =
		collection
			.find(filter(ownerId, name))
			.firstOrNull()
			?.let { ParticlesList.fromDoc(it) }

	fun getAll(ownerId: String): List<ParticlesList> =
		collection
			.find(filterOwner(ownerId))
			.useCursor { cursor ->
				cursor
					.map { ParticlesList.fromDoc(it) }
					.toList()
			}

	fun createIfNeeded(list: ParticlesList): Boolean =
		trapDuplicateKeyException {
			collection.insertOne(list.toDoc())
		}

	fun delete(ownerId: String, name: String) {
		collection.deleteOne(filter(ownerId, name))
	}

	fun deleteAll(ownerId: String) {
		collection.deleteMany(filterOwner(ownerId))
	}

	fun copyAll(srcOwnerId: String, dstOwnerId: String) {
		collection
			.find(filterOwner(srcOwnerId))
			.useCursor { docs ->
				for (doc in docs) {
					val name = doc.getString("name")
					collection.replaceOne(
						filter(dstOwnerId, name),
						doc.apply {
							set("_id", "$dstOwnerId/$name")
							set("ownerId", dstOwnerId)
						},
						ReplaceOptions().upsert(true)
					)
				}
			}
	}

	private fun ParticlesList.toDoc() = Document().apply {
		this["_id"] = "$ownerId/$name"
		this["ownerId"] = ownerId
		this["name"] = name
		this["type"] = type.id
		this["source"] = source.id
	}

	private fun ParticlesList.Companion.fromDoc(doc: Document): ParticlesList =
		ParticlesList(
			ownerId = doc.getString("ownerId"),
			name = doc.getString("name"),
			type = ParticlesType.getOrThrow(doc.getString("type")),
			source = ParticlesSource.getOrThrow(doc.getString("source"))
		)
}


class Particles {

	companion object {

		object Keys {
			const val ownerId = "ownerId"
			const val name = "name"
			const val datumId = "datumId"
			const val nextId = "nextId"
			const val particles = "particles"
			// NOTE: this used to be used for this virions, but now it's for any particle with segmentation thresholds
			//       but don't change database keys unless you really need to
			const val threshold = "virionThreshold"
		}
	}

	private val collection = Database.db.getCollection("particles")

	init {
		collection.createIndex(Document().apply {
			this[Keys.ownerId] = 1
		})
		collection.createIndex(Document().apply {
			this[Keys.ownerId] = 1
			this[Keys.name] = 1
		})
	}


	private fun particleKey(particleId: Int): String =
		"$particleId"
		// just convert the int directly to a string
		// integer-looking string keys are apparently just fine in mongo

	private fun particleQualifiedKey(particleId: Int): String =
		"${Keys.particles}.${particleKey(particleId)}"

	private fun particleQualifiedKeyPath(particleId: Int, subKey: String): String =
		"${particleQualifiedKey(particleId)}.$subKey"

	private fun particleId(particleKey: String): Int? =
		particleKey.toIntOrNull()


	private fun filter(ownerId: String, name: String, datumId: String) =
		Filters.eq("_id", "$ownerId/$name/$datumId")

	private fun filterList(ownerId: String, name: String) =
		Filters.and(
			filterOwner(ownerId),
			Filters.eq(Keys.name, name)
		)

	private fun filterOwner(ownerId: String) =
		Filters.eq(Keys.ownerId, ownerId)


	private fun <T> getParticles(ownerId: String, name: String, datumId: String, mapper: (Document) -> T?): Map<Int,T> =
		collection
			.find(filter(ownerId, name, datumId))
			.firstOrNull()
			?.getDocument(Keys.particles)
			?.entries
			?.mapNotNull m@{ (key, value) ->
				val particleId = particleId(key)
					?: return@m null
				val particle = (value as? Document)
					?.let(mapper)
					?: return@m null
				particleId to particle
			}
			?.associate { it }
			?: emptyMap()

	fun getParticles2D(ownerId: String, name: String, datumId: String): Map<Int,Particle2D> =
		getParticles(ownerId, name, datumId) { doc ->
			Particle2D.fromDoc(doc)
		}

	fun getParticles3D(ownerId: String, name: String, datumId: String): Map<Int,Particle3D> =
		getParticles(ownerId, name, datumId) { doc ->
			Particle3D.fromDoc(doc)
		}

	fun countParticles(ownerId: String, name: String, datumId: String): Long? =
		collection
			.find(filter(ownerId, name, datumId))
			.firstOrNull()
			?.getDocument(Keys.particles)
			?.size
			?.toLong()
			// TODO: could optimize by making the database do the counting

	fun countAllParticles(ownerId: String, name: String): Long =
		collection
			.find(filterList(ownerId, name))
			.useCursor { cursor ->
				cursor
					.sumOf { it.getDocument(Keys.particles)?.size?.toLong() ?: 0 }
			}

	private fun <T> importParticles(ownerId: String, name: String, datumId: String, particles: Map<Int,T>, mapper: (T) -> Document) {

		val filter = filter(ownerId, name, datumId)

		collection.replaceOne(
			filter,
			Document().apply {
				this[Keys.ownerId] = ownerId
				this[Keys.name] = name
				this[Keys.datumId] = datumId
				this[Keys.nextId] = particles.size + 1
				this[Keys.particles] = Document().apply {
					for ((particleId, particle) in particles) {
						this[particleKey(particleId)] = mapper(particle)
					}
				}
			},
			ReplaceOptions().upsert(true)
		)
	}

	fun importParticles2D(ownerId: String, name: String, datumId: String, particles: Map<Int,Particle2D>) =
		importParticles(ownerId, name, datumId, particles) {
			Document().apply {
				it.toDoc(this)
			}
		}

	fun importParticles3D(ownerId: String, name: String, datumId: String, particles: Map<Int,Particle3D>) =
		importParticles(ownerId, name, datumId, particles) {
			Document().apply {
				it.toDoc(this)
			}
		}


	private fun addParticle(ownerId: String, name: String, datumId: String, doccer: Document.() -> Unit): Int {

		val filter = filter(ownerId, name, datumId)

		val particleId = synchronized(ParticleIdsLock) {
			// NOTE: this could be done using database transactions if we need (marginally) higher performance,
			// but using a mutex accomplishes the same safety and is much much simpler!

			// get the existing particle document, if any
			val doc = collection
				.find(filter)
				.projection(Projections.include(Keys.nextId))
				.firstOrNull()

			// read the next particle id
			val particleId = doc
				?.getInteger(Keys.nextId)
				?: 1

			// increment it
			val updates = mutableListOf(
				Updates.set(Keys.nextId, particleId + 1)
			)

			if (doc == null) {
				// create a new particles doc
				updates.add(Updates.set(Keys.ownerId, ownerId))
				updates.add(Updates.set(Keys.name, name))
				updates.add(Updates.set(Keys.datumId, datumId))
				updates.add(Updates.set(Keys.particles, Document().apply {
					this[particleKey(particleId)] = Document().apply {
						doccer()
					}
				}))
			} else {
				// update the existing particles doc
				updates.add(Updates.set(particleQualifiedKey(particleId), Document().apply {
					doccer()
				}))
			}

			collection.updateOne(
				filter,
				Updates.combine(updates),
				UpdateOptions().upsert(true)
			)

			particleId
		}

		return particleId
	}

	fun addParticle2D(ownerId: String, name: String, datumId: String, particle: Particle2D) =
		addParticle(ownerId, name, datumId) {
			particle.toDoc(this)
		}

	fun addParticle3D(ownerId: String, name: String, datumId: String, particle: Particle3D) =
		addParticle(ownerId, name, datumId) {
			particle.toDoc(this)
		}

	fun deleteParticle(ownerId: String, name: String, datumId: String, particleId: Int) {
		collection.updateOne(
			filter(ownerId, name, datumId),
			Updates.unset(particleQualifiedKey(particleId))
		)
	}

	fun deleteAllParticles(ownerId: String, name: String) {
		collection.deleteMany(filterList(ownerId, name))
	}

	fun deleteAllParticles(ownerId: String) {
		collection.deleteMany(filterOwner(ownerId))
	}

	private fun <T> importMetadata(ownerId: String, name: String, datumId: String, metadata: Map<Int,T>, mapper: (T) -> List<Pair<String,Any>>) {

		val updates = metadata
			.entries
			.flatMap { (particleId, m) ->
				mapper(m)
					.map { (key, value) ->
						Updates.set(particleQualifiedKeyPath(particleId, key), value)
					}
			}

		// can't update with zero updates (mongo throws an error),
		// so we don't need to do anything here
		if (updates.isEmpty()) {
			return
		}

		collection.updateOne(
			filter(ownerId, name, datumId),
			Updates.combine(updates),
			UpdateOptions().upsert(true)
		)
	}

	private fun setMetadata(ownerId: String, name: String, datumId: String, particleId: Int, key: String, value: Any?) {
		collection.updateOne(
			filter(ownerId, name, datumId),
			if (value != null) {
				Updates.set(particleQualifiedKeyPath(particleId, key), value)
			} else {
				Updates.unset(particleQualifiedKeyPath(particleId, key))
			}
		)
	}


	fun importThresholds(ownerId: String, name: String, datumId: String, thresholds: Map<Int,Int>) =
		importMetadata(ownerId, name, datumId, thresholds) { threshold ->
			listOf(
				Keys.threshold to threshold
			)
		}

	fun setThreshold(ownerId: String, name: String, datumId: String, particleId: Int, threshold: Int?) =
		setMetadata(ownerId, name, datumId, particleId, Keys.threshold, threshold)

	fun getThresholds(ownerId: String, name: String, datumId: String): Map<Int,Int> =
		getParticles(ownerId, name, datumId) { doc ->
			doc.getInteger(Keys.threshold)
		}

	fun copyAllParticles(ownerId: String, name: String, newName: String) {
		collection
			.find(filterList(ownerId, name))
			.useCursor { cursor ->
				for (doc in cursor) {

					val datumId = doc.getString(Keys.datumId)
						?: continue

					// re-insert the doc with a new name
					doc["_id"] = "$ownerId/$newName/$datumId"
					doc[Keys.name] = newName

					collection.insertOne(doc)
				}
			}
	}

	fun copyAllParticles(srcOwnerId: String, dstOwnerId: String) {
		collection
			.find(filterOwner(srcOwnerId))
			.useCursor { docs ->
				for (doc in docs) {
					val name = doc.getString(Keys.name)
					val datumId = doc.getString(Keys.datumId)
					collection.replaceOne(
						filter(dstOwnerId, name, datumId),
						doc.apply {
							set("_id", "$dstOwnerId/$name/$datumId")
							set(Keys.ownerId, dstOwnerId)
						},
						ReplaceOptions().upsert(true)
					)
				}
			}
	}

	fun renameAll(ownerId: String, oldName: String, newName: String) {
		collection
			.find(filterList(ownerId, oldName))
			.useCursor { docs ->
				for (doc in docs) {
					val datumId = doc.getString(Keys.datumId)
					collection.insertOne(
						doc.apply {
							set("_id", "$ownerId/$newName/$datumId")
							set(Keys.name, newName)
						}
					)
					collection.deleteOne(filter(ownerId, oldName, datumId))
				}
			}
	}
}


private object ParticleIdsLock


private fun Particle2D.toDoc(doc: Document) {
	doc["x"] = x.v
	doc["y"] = y.v
	doc["r"] = r.v
}
private fun Particle2D.Companion.fromDoc(doc: Document) =
	Particle2D(
		x = ValueUnbinnedI(doc.getNumberAsIntOrThrow("x")),
		y = ValueUnbinnedI(doc.getNumberAsIntOrThrow("y")),
		r = ValueUnbinnedF(doc.getDouble("r")),
	)

private fun Particle3D.toDoc(doc: Document) {
	doc["x"] = x.v
	doc["y"] = y.v
	doc["z"] = z.v
	doc["r"] = r.v
}
private fun Particle3D.Companion.fromDoc(doc: Document) =
	Particle3D(
		x = ValueBinnedI(doc.getNumberAsIntOrThrow("x")),
		y = ValueBinnedI(doc.getNumberAsIntOrThrow("y")),
		z = ValueBinnedI(doc.getNumberAsIntOrThrow("z")),
		r = ValueBinnedF(doc.getDouble("r"))
	)
