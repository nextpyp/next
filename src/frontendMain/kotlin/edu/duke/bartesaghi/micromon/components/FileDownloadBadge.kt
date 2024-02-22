package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.FileDownloadData
import io.kvision.html.Div
import io.kvision.html.span


class FileDownloadBadge(
	filetype: String
) : Div() {

	data class Info(
		val data: FileDownloadData,
		val href: String,
		val filename: String
	)

	val link = LinkBadge()
		.apply {
			leftElem.iconStyled("far fa-file", classes = setOf("icon"))
			leftElem.span(filetype)
		}

	init {
		add(link)
		showEmpty()
	}

	fun load(loader: suspend () -> Info?) {

		link.rightElem.removeAll()
		link.rightElem.loading()

		AppScope.launch {
			try {
				val info = loader()

				link.rightElem.removeAll()
				if (info != null) {
					link.rightElem.content = info.data.bytes.toBytesString()
					link.rightColor = LinkBadge.Color.Green
					link.href = info.href
					link.download = info.filename
				} else {
					link.rightElem.content = "none"
					link.rightColor = LinkBadge.Color.Grey
				}
			} catch (t: Throwable) {
				t.reportError("Failed to get file download data")
				link.rightElem.content = "error"
				link.rightColor = LinkBadge.Color.Red
			}
		}
	}

	fun showEmpty() {
		link.rightElem.removeAll()
		link.rightElem.content = "not available"
		link.href = null
		link.download = null
	}
}
