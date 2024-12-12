package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.linux.userprocessor.readerAs
import edu.duke.bartesaghi.micromon.use
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.util.pipeline.*
import io.kvision.remote.DummyWsSessionModule
import io.kvision.remote.injector
import java.nio.file.Path


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


@Suppress("UnusedReceiverParameter")
val ContentType.Image.Svgz: ContentType get() =
	ContentType("image", "svg+xml")


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
