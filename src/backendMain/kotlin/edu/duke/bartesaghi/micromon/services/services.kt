package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.linux.userprocessor.readerAs
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.response.*
import io.ktor.util.cio.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.kvision.remote.DummyWsSessionModule
import io.kvision.remote.injector
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime


interface Service {
	var call: ApplicationCall
}

inline fun <reified S:Service> PipelineContext<Unit, ApplicationCall>.getService(): S =
	call.injector
		.createChildInjector(DummyWsSessionModule())
		.getInstance(S::class.java)
		.apply {
			call = this@getService.call
		}


@Suppress("UnusedReceiverParameter")
val ContentType.Image.WebP: ContentType get() =
	ContentType("image", "webp")


suspend fun ApplicationCall.respondStreamingWriter(path: Path, osUsername: String?) {
	path.readerAs(osUsername).use { reader ->
		respondBytesWriter {
			while (true) {
				val chunk = reader.read()
					?: break
				writeFully(chunk, 0, chunk.size)
			}
		}
	}
}


suspend fun ApplicationCall.respondOk() =
	respondText("")


suspend fun ApplicationCall.respondFile(path: Path, contentType: ContentType) =
	respond(object : OutgoingContent.ReadChannelContent() {

		override val contentType: ContentType =
			contentType

		override val contentLength: Long get() =
			path.fileSize()

		override fun readFrom(): ByteReadChannel =
			path.readChannel()

		override fun readFrom(range: LongRange): ByteReadChannel =
			path.readChannel(range.first, range.last)
	})


suspend fun ApplicationCall.respondCacheControlled(etag: String, responder: suspend () -> Unit) {

	// check the etag
	when (val result = EntityTagVersion(etag).check(request.headers)) {

		// if the browser's cache is up-to-date, return a lightweight response without a payload
		VersionCheckResult.NOT_MODIFIED,
		VersionCheckResult.PRECONDITION_FAILED -> respond(result.statusCode)

		// otherwise, read the resource and return it in the response body
		VersionCheckResult.OK -> {
			response.header(HttpHeaders.ETag, etag)
			response.header(HttpHeaders.CacheControl, "no-cache")
			responder()
		}
	}
}


fun Path.timestampEtag(): String? =
	mtime()
		?.toMillis()
		?.toString()


fun ApplicationCall.disableDefaultCompression() {
	attributes.put(Compression.SuppressionAttribute, true)
}
