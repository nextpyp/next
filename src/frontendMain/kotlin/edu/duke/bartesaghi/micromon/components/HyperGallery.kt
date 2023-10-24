package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.ImageSizes
import edu.duke.bartesaghi.micromon.divideUp
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.Div
import js.getHTMLElement
import js.hyperlist.HyperList
import js.hyperlist.hyperList
import kotlinx.browser.document
import kotlinx.html.*
import kotlinx.html.dom.create
import kotlinx.html.js.onClickFunction
import org.w3c.dom.HTMLElement
import kotlin.math.min


/**
 * An image gallery that can efficiently handle a large number of images.
 * Based on the HyperList js library
 */
class HyperGallery<T:HasID>(
	val data: List<T>,
	val html: FlowOrPhrasingContent.(T) -> Unit,
	val linker: (T, Int) -> Unit
) : Div(classes = setOf("hyper-gallery")) {

	companion object {
		private var nextGalleryId = 1
		const val defaultItemPadding = 20
	}

	private val galleryId = nextGalleryId++
	private var hyperlist: HyperList? = null

	fun loadIfNeeded(itemPadding: Int = defaultItemPadding, itemSizesLoader: suspend () -> ImageSizes) {

		if (hyperlist != null) {
			return
		}

		AppScope.launch {
			val itemSizes = itemSizesLoader()
			loadIfNeeded(itemSizes, itemPadding)
		}
	}

	/**
	 * Call once to initialize the hyper gallery.
	 * If this component is not in the real DOM, nothing will be loaded.
	 */
	fun loadIfNeeded(itemSizes: ImageSizes, itemPadding: Int = defaultItemPadding) {

		if (hyperlist != null) {
			return
		}
		val elem = getHTMLElement()
			?: return

		// how many thumbnails can fit in a row?
		val itemsPerRow = elem.clientWidth/(itemSizes.width + itemPadding)

		val hyperlist = hyperList(
			classes = setOf("thumbnails-hyperlist"),
			itemHeight = itemSizes.height + itemPadding,
			items = { data.size.divideUp(itemsPerRow) },
			generator = { row ->

				val startIndex = itemsPerRow*row
				val stopIndex = min(startIndex + itemsPerRow, data.size)

				// create the HTML content for thumbnail images
				// NOTE: don't use KVision's shadow DOM here, use raw DOM nodes,
				// since we're dealing with hyperlist's generator function
				document.create.div {
					for (i in startIndex until stopIndex) {

						// show newest image at the top, so invert the indices
						val index = data.size - i - 1
						val datum = data[index]

						a("/#", classes = "thumbnail") {
							id = entryId(index)
							html(datum)
							span(classes = "label") {
								+datum.id
							}
							onClickFunction = event@{
								linker(datum, index)
								it.preventDefault()
							}
						}
					}
				}
			}
		)
		this.hyperlist = hyperlist

		// the hyperlist uses a `fixed` css position (ie, window-relative), so the scrolling works correctly
		// move the top to be just below the tabs, so it looks like was actually positioned using the usual 'static' rules
		val pos = elem
			.getBoundingClientRect()
			.top
		hyperlist.elem.style.top = "${pos}px"
	}

	private fun entryId(index: Int): String =
		"hyper-gallery-entry-$galleryId-$index"

	fun elem(index: Int): HTMLElement? =
		document.getElementById(entryId(index))
			as? HTMLElement
}
