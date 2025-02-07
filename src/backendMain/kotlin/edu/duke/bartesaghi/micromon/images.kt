package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.linux.userprocessor.WebCacheDir
import edu.duke.bartesaghi.micromon.linux.userprocessor.writeBytesAs
import edu.duke.bartesaghi.micromon.services.*
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
import kotlin.io.path.div


enum class ImageType(
	val contentType: ContentType,
	val extension: String,
	val getPlaceholder: (ImageSize) -> ByteArray,
	val extraHeaders: List<Pair<String,String>> = emptyList(),
	val resizable: Boolean = true,
) {
	Jpeg(
		ContentType.Image.JPEG,
		"jpg",
		getPlaceholder = Resources::placeholderJpg
	),

	Png(
		ContentType.Image.PNG,
		"png",
		getPlaceholder = Resources::placeholderPng
	),

	Webp(
		ContentType.Image.WebP,
		"webp",
		getPlaceholder = Resources::placeholderWebp
	),

	Svgz(
		ContentType.Image.SVG,
		"svgz",
		getPlaceholder = { _ -> Resources.placeholderSvgz() },
		extraHeaders = listOf(HttpHeaders.ContentEncoding to "gzip"),
		resizable = false
	);


	val mimeType: String =
		contentType.toString()
}


suspend fun ApplicationCall.respondImage(path: Path, type: ImageType) {

	// disable Ktor's default gzip compression for this response, since images are already compressed
	disableDefaultCompression()

	val etag = path.timestampEtag()
		?: throw FileNotFoundException(path.toString())

	respondCacheControlled(etag) {
		for ((name, value) in type.extraHeaders) {
			response.headers.append(name, value)
		}
		respondFile(path, type.contentType)
	}
}


suspend fun ApplicationCall.respondImagePlaceholder(type: ImageType, size: ImageSize) {

	// disable Ktor's default gzip compression for this response, since images are already compressed
	disableDefaultCompression()

	respondCacheControlled("placeholder") {
		respondBytes(type.getPlaceholder(size), type.contentType)
	}
}


suspend fun ApplicationCall.respondImageSized(
	path: Path,
	type: ImageType,
	size: ImageSize,
	dir: WebCacheDir,
	key: WebCacheDir.Key,
	transformer: ((BufferedImage) -> BufferedImage)? = null
) {

	if (!path.exists()) {
		respondImagePlaceholder(type, size)
		return
	}

	val resizedPath = dir.resizeImage(path, type, size, key, transformer)
	respondImage(resizedPath, type)
}


/**
 * Returns the image as a byte array, resizing to the desired size.
 * Can cache the resized image if required.
 */
suspend fun WebCacheDir.resizeImage(
	path: Path,
	type: ImageType,
	size: ImageSize,
	/** a unique identifier for the image file, among all the images that might be cached in the same folder */
	key: WebCacheDir.Key,
	transformer: ((BufferedImage) -> BufferedImage)? = null
): Path {

	// if the image isn't resizable, there's nothing to do
	if (!type.resizable) {
		return path
	}

	val cacheName = "${key.id}.${size.id}.${type.extension}"
	val cachePath = this.path / cacheName

	// if we already have the cached image, use that
	if (cachePath.exists()) {
		return cachePath
	}

	// cache miss, check for the source image
	if (!path.exists()) {
		throw FileNotFoundException(path.toString())
	}

	// read the image, if we can
	val reader = ImageIO.getImageReadersByMIMEType(type.mimeType)
		.takeIf { it.hasNext() }
		?.next()
		?: throw UnsupportedOperationException("ImageIO doesn't know how to read $type image at $path")
	var image = slowIOs {
		FileImageInputStream(path.toFile()).use {
			reader.input = it
			reader.read(0)
		}
	}

	// apply the image transformer if needed
	if (transformer != null) {
		image = transformer(image)
	}

	// resize the image, but keep the same aspect ratio
	val resized = image
		.resize(size.approxWidth)
		.write(type)

	// finally, write the resized image to the cache
	this.createIfNeeded()
	cachePath.writeBytesAs(osUsername, resized)

	return cachePath
}


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
	val writer = ImageIO.getImageWritersByMIMEType(imageType.mimeType)
		.takeIf { it.hasNext() }
		?.next()
		?: throw NoSuchElementException("no image writer found for mime type: ${imageType.mimeType}")

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
