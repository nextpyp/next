package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.Identified


enum class ImageSize(override val id: String, val approxWidth: Int): Identified {

	Small("small", 256),
	Medium("medium", 512),
	Large("large", 1024);

	companion object {

		operator fun get(id: String): ImageSize? =
			id.lowercase().let { idlo ->
				values().find { it.id == idlo }
			}
	}
}
