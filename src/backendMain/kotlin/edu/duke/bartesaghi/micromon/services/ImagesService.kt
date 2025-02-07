package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.Resources
import edu.duke.bartesaghi.micromon.parseSize
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*


/**
 * a route for serving generic images
 */
object ImagesService {

	fun init(routing: Routing) {

		routing.route("img") {
			get("placeholder/{size}") {

				val size = parseSize()

				call.respondBytes(Resources.placeholderWebp(size), ContentType.Image.WebP)
			}
		}
	}
}
