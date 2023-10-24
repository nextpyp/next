package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.services.ImageSize
import edu.duke.bartesaghi.micromon.services.TwoDClassesData
import edu.duke.bartesaghi.micromon.sessions.SingleParticleSession
import edu.duke.bartesaghi.micromon.sessions.pypNamesOrThrow
import org.bson.Document
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.div


class TwoDClasses(
	val ownerId: String,
	val twoDClassesId: String,
	val created: Instant = Instant.now()
) {

	companion object {

		fun fromDoc(doc: Document) =
			TwoDClasses(
				ownerId = doc.getString("ownerId") ?: throw NoSuchElementException("2D classes document has no owner id"),
				twoDClassesId = doc.getString("twoDClassesId") ?: throw NoSuchElementException("2D classes document has no classes id"),
				created = Instant.ofEpochMilli(doc.getLong("timestamp") ?: throw NoSuchElementException("2D classes document has no timestamp"))
			)

		fun get(ownerId: String, classesId: String): TwoDClasses? =
			Database.twoDClasses.get(ownerId, classesId)
				?.let { fromDoc(it) }

		fun getAll(ownerId: String): List<TwoDClasses> =
			Database.twoDClasses.getAll(ownerId) { cursor ->
				cursor
					.map { fromDoc(it) }
					.toList()
			}

	}

	fun toDoc() = Document().apply {
		this["ownerId"] = ownerId
		this["twoDClassesId"] = twoDClassesId
		this["timestamp"] = created.toEpochMilli()
	}

	fun write() =
		Database.twoDClasses.write(ownerId, twoDClassesId, toDoc())

	fun toData() =
		TwoDClassesData(
			ownerId,
			twoDClassesId,
			created.toEpochMilli()
		)

	fun imagePath(session: SingleParticleSession): Path =
		session.pypDir(session.newestArgs().pypNamesOrThrow()) / "class2d" / "${twoDClassesId}_classes.webp"

	fun readImage(session: SingleParticleSession, size: ImageSize): ByteArray {

		val path = imagePath(session)
		val cacheInfo = ImageCacheInfo(session.wwwDir, "two2classes.$twoDClassesId")

		return size.readResize(path, ImageType.Webp, cacheInfo)
			?: Resources.placeholderJpg(size)
	}
}
