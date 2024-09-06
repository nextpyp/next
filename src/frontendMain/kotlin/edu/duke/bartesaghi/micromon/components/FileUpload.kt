package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.toast.Toast
import js.getHTMLElementOrThrow
import kotlinext.js.jsObject
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.html.InputType
import kotlinx.html.dom.create
import kotlinx.html.js.input
import org.w3c.files.get
import org.w3c.xhr.FormData


class FileUpload(
	val url: String,
	label: String,
	val filename: String,
	val accept: String? = null
) : Div(classes = setOf("file-upload")) {

	companion object {

		const val UPLOAD_ICON = "fas fa-file-upload"
	}


	private val link = LinkBadge()
		.apply {
			leftElem.iconStyled("far fa-file", classes = setOf("icon"))
			leftElem.span(label)
		}

	private val uploadButton = Button("", icon = UPLOAD_ICON)
		.apply {
			title = "Upload a file"
			enabled = false
			onClick {
				pickFile()
			}
		}

	private val deleteButton = Button("", icon = "fas fa-trash-alt")
		.apply {
			title = "Delete the uploaded file"
			enabled = false
			onClick {
				deleteFile()
			}
		}

	private val formElem = Div().apply {
		// keep the form active (ie, in the DOM), but invisible to the user
		setStyle("display", "none")
	}

	init {
		val self = this

		add(link)
		div(classes = setOf("buttons")) {
			add(self.uploadButton)
			add(self.deleteButton)
		}
		add(formElem)

		AppScope.launch {
			load()
		}
	}

	private fun FileUploadOperation.url() =
		"$url/$id"

	private suspend fun load() {

		link.rightElem.loading()

		try {
			val info = Services.Raw.get<Option<FileUploadData>>(FileUploadOperation.Data.url())
				.unwrap()
			show(info)
		} catch (t: Throwable) {
			t.reportError("Failed to get file upload data")
			link.rightElem.content = "error"
			link.rightColor = LinkBadge.Color.Red
			return
		}

		// enable the upload button only the data loads sucessfully
		uploadButton.enabled = true
	}

	private fun show(info: FileUploadData?) {

		link.rightElem.removeAll()

		if (info != null) {
			link.rightElem.content = info.bytes.toBytesString()
			link.rightColor = LinkBadge.Color.Green
			link.href = FileUploadOperation.Get.url()
			link.download = filename
			deleteButton.enabled = true
		} else {
			link.rightElem.content = "none"
			link.rightColor = LinkBadge.Color.Grey
			deleteButton.enabled = false
		}
	}

	private fun pickFile() {

		formElem.removeAll()
		val elem = formElem.getHTMLElementOrThrow()

		// I don't think kvision has file upload widgets?
		// So just use raw HTML here, since it's easy enough

		// add a file picker
		val filePicker = document.create.input(InputType.file)
		accept?.let { filePicker.accept = it }
		elem.appendChild(filePicker)

		// show the browser's file picker dialog
		filePicker.click()
		filePicker.onchange = e@{

			val file = filePicker.files
				?.get(0)
				?: return@e undefined

			// upload the file
			val pResponse = window.fetch(
				FileUploadOperation.Set.url(),
				jsObject {
					method = "POST"
					body = FormData().apply {
						append("file", file)
					}
				}
			)

			// show that the upload started
			uploadButton.enabled = false
			uploadButton.icon = null
			val loading = uploadButton.loading()

			AppScope.launch {

				try {
					// wait for the upload to finish
					pResponse.await()
				} catch (ex: dynamic) {
					Toast.error("Failed to upload file: $ex")
				} finally {
					uploadButton.enabled = true
					uploadButton.icon = UPLOAD_ICON
					uploadButton.remove(loading)
				}

				// upload complete! refresh the info
				load()
			}

			undefined
		}
	}

	private fun deleteFile() {
		AppScope.launch {
			try {
				Services.Raw.send(FileUploadOperation.Delete.url())
			} catch (ex: dynamic) {
				Toast.error("Failed to delete uploaded file: $ex")
			}
			show(null)
		}
	}
}
