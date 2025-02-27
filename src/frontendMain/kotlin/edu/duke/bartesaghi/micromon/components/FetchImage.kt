package edu.duke.bartesaghi.micromon.components

import com.github.snabbdom.VNode
import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.GarbageNotifier
import io.kvision.core.Container
import io.kvision.core.Widget
import js.WeakRef
import js.getHTMLElement
import kotlinext.js.jsObject
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.url.URL
import org.w3c.fetch.*


/**
 * An image element that exclusively uses fetch() to load its images,
 * thus bypassing the secret in-memory cache used by browsers that can't be controlled by cache-control headers
 */
open class FetchImage(
	classes: Set<String> = setOf(),
	init: (FetchImage.() -> Unit)? = null
) : Widget(classes) {

	init {
		@Suppress("LeakingThis")
		init?.invoke(this)

		addAfterInsertHook {
			syncSrc()
		}
	}

	final override fun addAfterInsertHook(hook: (VNode) -> Unit) =
		super.addAfterInsertHook(hook)

	override fun render(): VNode {
		return render("img")
	}

	private var fetchedUrl: FetchedUrl? = null
	private var broken: Boolean = false

	fun fetch(url: String) {

		AppScope.launch {

			// use fetch() to directly request (or revalidate) the image, rather
			val response = window.fetch(url, jsObject {
				cache = RequestCache.NO_CACHE
			}).await()

			when (response.status) {

				// got an image, but we're not sure if it's a new image or a cached one
				// NOTE: responses retrieved from the cache will have status 200 Ok,
				//       even if the browser actually revalidated the request and the server returned 304 Not Modified
				200.toShort() -> {
					fetchedUrl = FetchedUrl.get(url)
						?.takeIf { it.etag == response.etag() }
						?: FetchedUrl.make(url, response)
				}

				// something else (like a 404, 403, or 500):
				// treat this as a broken image
				else -> {
					broken = true
					fetchedUrl = null
				}
			}

			syncSrc()
		}
	}

	fun revalidate() {
		val fetchedUrl = fetchedUrl
			?: return
		fetch(fetchedUrl.url)
	}

	private fun syncSrc() {

		// get the image, if it's even in the real DOM
		val img = getHTMLElement()
			as? HTMLImageElement
			?: return

		if (broken) {
			img.src = ""
		} else {
			val fetchedUrl = fetchedUrl
				?: return
			img.setAttribute("fetched-src", fetchedUrl.url)
			img.src = fetchedUrl.blobUrl
		}
	}


	class FetchedUrl(
		val url: String,
		val etag: String,
		val blobUrl: String
	) {

		companion object {

			// NOTE: this cache should only store weak references to the FetchedUrl instances,
			//       so they can still be collected by the JS garbage collector
			private val cache = HashMap<String,WeakRef<FetchedUrl>>()
			private val garbage = GarbageNotifier()

			fun get(url: String): FetchedUrl? {
				val ref = cache[url]
					?: return null
				val fetchedUrl = ref.deref()
				return if (fetchedUrl != null) {
					fetchedUrl
				} else {
					// fetched URL was garbage collected: clean up the cache entry
					cache.remove(url)
					null
				}
			}

			suspend fun make(url: String, response: Response): FetchedUrl {

				val blobUrl = URL.createObjectURL(response.blob().await())

				val fetchedUrl = FetchedUrl(
					url,
					etag = response.etag(),
					blobUrl = blobUrl
				)
				val reg = garbage.register(fetchedUrl) {
					URL.revokeObjectURL(blobUrl)
					cache.remove(url)
				}
				cache[url] = reg.target
				return fetchedUrl
			}
		}
	}
}


fun Container.fetchImage(
	classes: Set<String> = setOf(),
	init: (FetchImage.() -> Unit)? = null
): FetchImage {
	val image = FetchImage(classes, init)
	this.add(image)
	return image
}


private fun Response.etag(): String =
	headers.get("etag")
		?: throw Error("Image response has no etag: it should not be used with FetchImage\n\turl: $url")
