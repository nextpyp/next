package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.batch
import io.kvision.html.*


class LinkBadge : Div(classes = setOf("link-badge")) {

	enum class Color(val id: String) {
		Grey("grey"),
		Green("green"),
		Red("red")
	}

	val leftElem = Div(classes = setOf("left"))
	val rightElem = Div(classes = setOf("right"))

	var href: String? = null
		set(value) {
			field = value
			update()
		}

	var download: String? = null
		set(value) {
			field = value
			update()
		}

	var rightColor: Color = Color.Grey
		set(value) {
			field = value
			update()
		}

	private fun update() {
		batch {

			removeAll()

			val href = href
			val container =
				if (href != null) {
					val a = tag(TAG.A)
					a.setAttribute("href", href)
					download?.let { a.setAttribute("download", it) }
					a
				} else {
					div()
				}

			container.addCssClass("link-badge-container")
			container.add(leftElem)
			container.add(rightElem)

			// update the right color
			Color.values().forEach { rightElem.removeCssClass(it.id) }
			rightElem.addCssClass(rightColor.id)
		}
	}
}
