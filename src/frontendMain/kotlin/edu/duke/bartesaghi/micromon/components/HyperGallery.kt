package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.ImageSizes
import edu.duke.bartesaghi.micromon.divideUp
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.Div
import io.kvision.html.div
import js.ResizeObserver
import js.getHTMLElement
import js.hyperlist.hyperList
import kotlinx.browser.document
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.dom.create
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import kotlin.math.min


/**
 * An image gallery that can efficiently handle a large number of images.
 * Based on the HyperList js library
 */
class HyperGallery<T:HasID>(
	val data: List<T>,
) : Div(classes = setOf("hyper-gallery")) {

	companion object {

		private var nextGalleryId = 1
	}

	private val galleryId = nextGalleryId++
	private var itemsPerRow: Int = 1

	var html: (T) -> HTMLElement? = { null }
	var linker: (T, Int) -> Unit = { _, _ -> }

	var imageSizes: ImageSizes? = null
		set(value) {
			field = value
			update()
		}

	var itemPadding: Int = 20
		set(value) {
			field = value
			update()
		}

	private var emptyMessage = div("(No items to show yet)", classes = setOf("empty", "spaced"))

	// make a hyperlist, but put multiple items on a single row
	private var hyperlist = hyperList(classes = setOf("thumbnails-hyperlist")) { row ->

		val startIndex = itemsPerRow*row
		val stopIndex = min(startIndex + itemsPerRow, data.size)

		// create the HTML content for thumbnail images
		// NOTE: don't use KVision's shadow DOM here, use raw DOM nodes,
		// since we're dealing with hyperlist's generator function
		val rowElem = document.create.div()

		for (i in startIndex until stopIndex) {

			// show newest image at the top, so invert the indices
			val index = data.size - i - 1
			val datum = data[index]

			rowElem.append(document.create.a("/#", classes = "thumbnail").apply {

				// generate a globally unique id for the element
				id = entryId(index)

				// get the item HTML from the caller
				html(datum)
					?.let { append(it) }

				// draw a label over the top of it
				append.span(classes = "label") {
					+datum.id
				}

				// wire up events
				onclick = { event ->
					linker(datum, index)
					event.preventDefault()
				}
			})
		}

		rowElem
	}

	init {
		update()

		// automatically update after changing widths
		var width: Double? = null
		ResizeObserver e@{ entries ->
			val entry = entries.firstOrNull()
				?: return@e
			if (width != entry.contentRect.width) {
				width = entry.contentRect.width
				update()
			}
		}.observe(hyperlist.elem)
	}

	/**
	 * Returns an HTML image element that sets the imageSizes of the gallery
	 * on the first image load
	 */
	fun listenToImageSize(img: HTMLImageElement): HTMLImageElement {

		// attach the listner if we still need image sizes
		if (imageSizes == null) {
			img.onload = {

				// update the image size, if we still need it
				if (imageSizes == null) {
					// leave this one in, might make debugging easier someday, and it won't generate much log spam
					console.log("HyperGallery: img load", img.src, img.naturalWidth, img.naturalHeight)
					imageSizes = ImageSizes(
						img.naturalWidth,
						img.naturalHeight
					)
				}
			}
		}

		return img
	}

	/**
	 * Call after changing the data list to refresh the hyper list
	 */
	fun update() {

		// update the empty message
		emptyMessage.visible = data.isEmpty()

		val imageSizes = imageSizes
			?: ImageSizes(128, 128)

		// get the new client area, if any
		val elem = getHTMLElement()
		if (elem != null) {

			// our CSS puts a `fixed` position on hyperlist (ie, window-relative), so the scrolling works correctly,
			// so move the top to be just below the tabs, so it looks like was actually positioned using the usual 'static' rules
			val bbox = elem
				.getBoundingClientRect()
			hyperlist.elem.style.top = "${bbox.top.toInt()}px"

			// also update the number of items per row based on the client width
			// NOTE: add a fudge factor to err on the side of putting fewer items on a single row,
			//       rather than have the browser try to wrap lines on us, or clip off the ends
			val fudge = 10
			itemsPerRow = bbox.width.toInt()/(imageSizes.width + itemPadding + fudge)
		}

		// update hyperlist config
		hyperlist.config.total = data.size.divideUp(itemsPerRow)
		hyperlist.config.itemHeight = imageSizes.height + itemPadding

		/* DEBUG
		console.log("update",
			"\nsize", data.size,
			"\nimageSizes", imageSizes,
			"\nbbox", elem?.getBoundingClientRect(),
			"\nitemsPerRow", itemsPerRow,
			"\ntotal", hyperlist.config.total,
			"\nitemHeight", hyperlist.config.itemHeight,
			"\nreverse", hyperlist.config.reverse
		)
		*/

		hyperlist.refreshHyperList()
	}

	private fun entryId(index: Int): String =
		"hyper-gallery-entry-$galleryId-$index"

	fun elem(index: Int): HTMLElement? =
		document.getElementById(entryId(index))
			as? HTMLElement
}
