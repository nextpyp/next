package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.linux.userprocessor.writeBytesAs
import edu.duke.bartesaghi.micromon.services.*
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
import kotlin.io.path.div


interface ImageType {

	val contentType: ContentType
	val extension: String
	val resizable: Boolean

	fun mimeType(): String =
		contentType.toString()

	suspend fun respondNotFound(call: ApplicationCall): Nothing =
		throw NotFoundException()

	suspend fun respond500(internalMessage: String): Nothing =
		throw IllegalStateException(internalMessage)


	data class SizeInfo(
		val size: ImageSize,
		val dir: WebCacheDir,
		val key: WebCacheDir.Key,
		val transformer: ((BufferedImage) -> BufferedImage)? = null
	)


	companion object {

		fun <T:ImageType> responseFinished(): T? = null

		suspend fun <T:ImageType> respondPath(
			type: T,
			call: ApplicationCall,
			path: Path,
			extraHeaders: List<Pair<String,String>> = emptyList()
		): T? {

			val etag = path.timestampEtag()
				?: return type

			call.respondCacheControlled(etag) {

				// disable Ktor's default gzip compression for this response, since images are already compressed
				call.disableDefaultCompression()

				for ((name, value) in extraHeaders) {
					call.response.headers.append(name, value)
				}

				call.respondFile(path, type.contentType)
			}

			return responseFinished()
		}

		suspend fun <T:ImageType> respondSized(
			type: T,
			call: ApplicationCall,
			path: Path,
			sizeInfo: SizeInfo,
			extraHeaders: List<Pair<String,String>> = emptyList()
		): T? {

			if (!type.resizable) {
				throw IllegalArgumentException("image type $type is not resizable")
			}

			// make sure the source image exists, regardless of the cache
			val srcMtime = path.mtime()
				?: return type

			// build the path of the cached image
			val cachePath = sizeInfo.dir.path / "${sizeInfo.key.id}.${sizeInfo.size.id}.${type.extension}"

			// if we already have the cached image that's newer than the source image, use that
			val cacheMtime = cachePath.mtime()
			if (cacheMtime != null && cacheMtime > srcMtime) {
				return respondPath(type, call, cachePath, extraHeaders)
			}

			// cache miss! resize the source image

			// read the image, if we can
			val reader = ImageIO.getImageReadersByMIMEType(type.mimeType())
				.takeIf { it.hasNext() }
				?.next()
				?: throw UnsupportedOperationException("ImageIO doesn't know how to read $type image at $path")
			var image = slowIOs {
				FileImageInputStream(path.toFile()).use {
					reader.input = it
					reader.read(0)
				}
			}

			// apply the image transformer before resizing if needed
			sizeInfo.transformer?.let { f ->
				image = f(image)
			}

			// resize the image, but keep the same aspect ratio
			val resized = image
				.resize(sizeInfo.size.approxWidth)
				.write(type)

			// finally, write the resized image to the cache file
			sizeInfo.dir.createIfNeeded()
			cachePath.writeBytesAs(sizeInfo.dir.osUsername, resized)

			return respondPath(type, call, cachePath, extraHeaders)
				?.respond500("resized image was not found at: $cachePath")
		}

		suspend fun <T:ImageType> respondBytes(
			type: T,
			call: ApplicationCall,
			bytes: ByteArray,
			etag: String,
			extraHeaders: List<Pair<String,String>> = emptyList()
		) {
			call.respondCacheControlled(etag) {

				// disable Ktor's default gzip compression for this response, since images are already compressed
				call.disableDefaultCompression()

				for ((name, value) in extraHeaders) {
					call.response.headers.append(name, value)
				}

				call.respondBytes(bytes, type.contentType)
			}
		}
	}


	object Jpeg : ImageType {

		override val contentType = ContentType.Image.JPEG
		override val extension = "jpg"
		override val resizable = true

		suspend fun respond(call: ApplicationCall, path: Path): Jpeg? =
			respondPath(this, call, path)

		suspend fun respondSized(call: ApplicationCall, path: Path, sizeInfo: SizeInfo): Jpeg? =
			respondSized(this, call, path, sizeInfo)

		suspend fun respondPlaceholder(call: ApplicationCall, size: ImageSize) =
			respondBytes(this, call, Resources.placeholderJpg(size), "placeholder")
	}

	object Png : ImageType {

		override val contentType = ContentType.Image.PNG
		override val extension = "png"
		override val resizable = true

		suspend fun respond(call: ApplicationCall, path: Path): Png? =
			respondPath(this, call, path)

		suspend fun respondSized(call: ApplicationCall, path: Path, sizeInfo: SizeInfo): Png? =
			respondSized(this, call, path, sizeInfo)

		suspend fun respondPlaceholder(call: ApplicationCall, size: ImageSize) =
			respondBytes(this, call, Resources.placeholderPng(size), "placeholder")
	}

	object Webp : ImageType {

		override val contentType = ContentType.Image.WebP
		override val extension = "webp"
		override val resizable = true

		suspend fun respond(call: ApplicationCall, path: Path): Webp? =
			respondPath(this, call, path)

		suspend fun respondSized(call: ApplicationCall, path: Path, sizeInfo: SizeInfo): Webp? =
			respondSized(this, call, path, sizeInfo)

		suspend fun respondPlaceholder(call: ApplicationCall, size: ImageSize) =
			respondBytes(this, call, Resources.placeholderWebp(size), "placeholder")
	}

	object Svgz : ImageType {

		override val contentType = ContentType.Image.SVG
		override val extension = "svgz"
		override val resizable = false

		val extraHeaders = listOf(HttpHeaders.ContentEncoding to "gzip")

		suspend fun respond(call: ApplicationCall, path: Path): Svgz? =
			respondPath(this, call, path, extraHeaders)

		suspend fun respondPlaceholder(call: ApplicationCall) =
			respondBytes(this, call, Resources.placeholderSvgz(), "placeholder", extraHeaders)
	}
}


fun ImageSize.info(
	dir: WebCacheDir,
	key: WebCacheDir.Key,
	transformer: ((BufferedImage) -> BufferedImage)? = null
) = ImageType.SizeInfo(this, dir, key, transformer)


private val resizeLock = Object()
private val isFirstResize = AtomicBoolean(true)

fun BufferedImage.resize(width: Int): BufferedImage {
	val resizedImage = BufferedImage(
		width,
		(width * this.height / this.width).coerceAtLeast(1),
		this.type
	)
	return AffineTransformOp(
		AffineTransform.getScaleInstance(
			resizedImage.width.toDouble()/this.width.toDouble(),
			resizedImage.height.toDouble()/this.height.toDouble()
		),
		AffineTransformOp.TYPE_BILINEAR
	).let { op ->
		// There's a bug in some JVMs that causes a deadlock
		// when trying to accesss 2d graphics from multiple threads.
		// https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6995195
		// It's currently marked as "wontfix" =(
		// So we'll have to work around it by sychronizing all image resizes.
		// TODO: looks like this bug is finally fixed in JVM v21? can we update?
		//       https://bugs.openjdk.org/browse/JDK-6995195
		// But for now, we can probably make the workaround better by not blocking here:
		// Instead of serializing all images resizes (using a single CPU core!),
		// we'll only guarantee that the first image resize doesn't run concurrently with any other image resize.
		// The bug in the JVM is in the static initializer of the ImageIO resize routines,
		// so once we get past the first image resize, we don't need to worry about deadlocks anymore.
		if (isFirstResize.get()) {
			synchronized (resizeLock) {
				op.filter(this, resizedImage)
					.also { isFirstResize.set(false) }
			}
		} else {
			op.filter(this, resizedImage)
		}
	}
}

fun BufferedImage.write(imageType: ImageType): ByteArray {

	// get the writer for this image format
	val writer = ImageIO.getImageWritersByMIMEType(imageType.mimeType())
		.takeIf { it.hasNext() }
		?.next()
		?: throw NoSuchElementException("no image writer found for mime type: ${imageType.mimeType()}")

	// write to a byte buffer
	val out = ByteArrayOutputStream()
	val imgout = ImageIO.createImageOutputStream(out)
	writer.output = imgout
	try {
		writer.write(this)
	} finally {
		writer.dispose()
		imgout.flush()
	}

	return out.toByteArray()
}
