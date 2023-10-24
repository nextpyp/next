package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.services.ImageSize
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
import kotlin.io.path.div
import kotlin.io.path.writeBytes


enum class ImageType(val mimeType: String, val extension: String) {
	Jpeg("image/jpeg", "jpg"),
	Png("image/png", "png"),
	Webp("image/webp", "webp")
}


data class ImageCacheInfo(
	/** where the cached image should be kept */
	val dir: Path,
	/** a unique identifier for the image file, among all the images that might be cached in the same folder */
	val key: String
) {

	fun path(type: ImageType, size: ImageSize): Path =
		dir / "$key.${size.id}.${type.extension}"
}


/**
 * Returns the image as a byte array, resizing to the desired size.
 * Can cache the resized image if required.
 */
fun ImageSize.readResize(
	path: Path,
	type: ImageType,
	cacheInfo: ImageCacheInfo? = null,
	transformer: ((BufferedImage) -> BufferedImage)? = null
): ByteArray? {

	// if we're caching, and we already have the cached image, use that
	val cachePath = cacheInfo?.path(type, this)
	if (cachePath?.exists() == true) {
		return cachePath.readBytes()
	}

	// cache miss, check for the source image
	if (!path.exists()) {
		return null
	}

	// read the image, if we can
	val reader = ImageIO.getImageReadersByMIMEType(type.mimeType)
		.takeIf { it.hasNext() }
		?.next()
		?: throw UnsupportedOperationException("ImageIO doesn't know how to read $type image at $path")
	var image = FileImageInputStream(path.toFile()).use {
		reader.input = it
		reader.read(0)
	}

	// apply the image transformer if needed
	if (transformer != null) {
		image = transformer(image)
	}

	// resize the image, but keep the same aspect ratio
	val resized = image
		.resize(approxWidth)
		.write(type)

	// if we're caching, save the file
	if (cachePath != null) {
		cachePath.parent.createDirsIfNeeded()
		cachePath.writeBytes(resized)
	}

	return resized
}


private val resizeLock = Object()

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
		synchronized (resizeLock) {
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
