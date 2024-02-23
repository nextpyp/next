package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.FileDownloadBadge
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.errorMessage
import edu.duke.bartesaghi.micromon.loading
import edu.duke.bartesaghi.micromon.services.FileDownloadData
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.Services
import edu.duke.bartesaghi.micromon.services.unwrap
import edu.duke.bartesaghi.micromon.views.PathableTab
import edu.duke.bartesaghi.micromon.views.RegisterableTab
import edu.duke.bartesaghi.micromon.views.TabRegistrar
import io.kvision.core.onEvent
import io.kvision.form.text.TextInput
import io.kvision.html.Button
import io.kvision.html.Div
import io.kvision.html.div


class MetadataTab(
	val job: JobData
) : Div(classes = setOf("metadata-tab")), PathableTab {

	companion object : RegisterableTab {

		const val pathFragment = "metadata"

		override fun registerRoutes(register: TabRegistrar) {
			register(pathFragment) {
				null
			}
		}
	}

	override var onPathChange = {}
	override var isActiveTab = false
	override fun path(): String = pathFragment

	private val searchText = TextInput()
		.apply {
			placeholder = "Tilt Series ID"
		}
	private val searchButton = Button("Search")
	private val resultsElem = Div(classes = setOf("spaced"))

	init {

		// layout the UI
		val self = this
		div(classes = setOf("search-bar", "spaced")) {
			add(self.searchText)
			add(self.searchButton)
		}
		add(self.resultsElem)

		// wire up events
		searchText.onEvent {
			input = {
				updateSearchButton()
			}
			keypress = { event ->
				if (event.key.lowercase() == "enter") {
					search()
				}
			}
		}
		searchButton.onClick {
			search()
		}

		updateSearchButton()
	}

	private fun query(): String? =
		searchText.value
			?.takeIf { it.isNotBlank() }

	private fun updateSearchButton() {
		searchButton.enabled = query() != null
	}

	private fun search() {

		val query = query()
			?: return

		resultsElem.removeAll()
		val loading = resultsElem.loading("Searching for tilt series")

		AppScope.launch {
			try {
				val data = Services.jobs.findTiltSeriesMetadataData(job.jobId, query)
					.unwrap()
				if (data == null) {
					showNone()
				} else {
					showResult(query, data)
				}
			} catch (t: Throwable) {
				resultsElem.errorMessage(t)
			} finally {
				resultsElem.remove(loading)
			}
		}
	}

	private fun showNone() {
		resultsElem.removeAll()
		resultsElem.div("Tilt Series metadata not found", classes = setOf("empty"))
	}

	private fun showResult(tiltSeriesId: String, data: FileDownloadData) {
		resultsElem.removeAll()
		val badge = FileDownloadBadge(".star file")
		badge.show(FileDownloadBadge.Info(
			data,
			"kv/jobs/${job.jobId}/data/$tiltSeriesId/tiltSeriesMetadata",
			"${job.jobId}_$tiltSeriesId.star"
		))
		resultsElem.add(badge)
	}
}
