package edu.duke.bartesaghi.micromon.services

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.util.pipeline.*
import io.kvision.remote.DummyWsSessionModule
import io.kvision.remote.injector


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
