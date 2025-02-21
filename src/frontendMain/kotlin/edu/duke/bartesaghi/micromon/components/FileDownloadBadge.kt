package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.FileDownloadData
import edu.duke.bartesaghi.micromon.services.Option
import edu.duke.bartesaghi.micromon.services.unwrap
import io.kvision.html.Div
import io.kvision.html.span


class FileDownloadBadge(
	filetype: String,
	var url: String? = null,
	var filename: String? = null,
	var loader: (suspend () -> Option<FileDownloadData>)? = null
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

	fun load() {

		link.rightElem.removeAll()
		link.rightElem.loading()

		val loader = loader
			?: run {
				showEmpty()
				return
			}

		AppScope.launch {
			try {
				val data = loader()
					.unwrap()
				show(data)
			} catch (t: Throwable) {
				t.reportError("Failed to get file download data")
				link.rightElem.content = "error"
				link.rightColor = LinkBadge.Color.Red
			}
		}
	}

	fun show(data: FileDownloadData?) {
		link.rightElem.removeAll()
		if (data != null) {
			link.rightElem.content = data.bytes.toBytesString()
			link.rightColor = LinkBadge.Color.Green
			link.href = url
			filename?.let { link.download = it }
		} else {
			link.rightElem.content = "none"
			link.rightColor = LinkBadge.Color.Grey
		}
	}

	fun showEmpty() {
		link.rightElem.removeAll()
		link.rightElem.content = "not available"
		link.href = null
		link.download = null
	}
}
