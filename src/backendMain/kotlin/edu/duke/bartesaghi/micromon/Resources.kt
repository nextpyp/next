package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.services.ImageSize


/**
 * I have no idea what voodoo KVision is doing to our class loaders,
 * but only the class loder in Application.main() seems to be able
 * to see our resources. So this class makes that class loader
 * available to the rest of the app.
 */
object Resources {

	private var loader: Class<*>? = null
	private val loaderOrThrow get() = loader ?: throw NoSuchElementException("no classloader set yet")

	val packageName: String = this::class.java.packageName

	fun init(loader: Class<*>) {
		this.loader = loader
	}

	fun readBytes(path: String): ByteArray {
		loaderOrThrow.getResourceAsStream(path).use { input ->
			return input?.readBytes()
				?: throw IllegalArgumentException("resource not found: $path")
		}
	}

	fun readText(path: String): String {
		loaderOrThrow.getResourceAsStream(path).use { input ->
			return input?.bufferedReader()?.readText()
				?: throw IllegalArgumentException("resource not found: $path")
		}
	}

	fun placeholderJpg(size: ImageSize): ByteArray =
		readBytes("/images/placeholder.${size.id}.jpg")

	fun placeholderPng(size: ImageSize): ByteArray =
		readBytes("/images/placeholder.${size.id}.png")

	fun placeholderWebp(size: ImageSize): ByteArray =
		readBytes("/images/placeholder.${size.id}.webp")

	fun placeholderSvgz() =
		readBytes("/images/placeholder.svgz")
}
