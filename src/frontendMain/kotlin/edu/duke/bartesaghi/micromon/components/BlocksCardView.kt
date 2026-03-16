package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.diagram.nodes.Node
import edu.duke.bartesaghi.micromon.diagram.nodes.clientInfo
import edu.duke.bartesaghi.micromon.services.ImageSize
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.ProjectData
import io.kvision.core.Display
import io.kvision.core.onEvent
import io.kvision.form.select.SelectInput
import io.kvision.form.text.TextInput
import io.kvision.html.*
import edu.duke.bartesaghi.micromon.routing


class BlocksCardView(
	private val project: ProjectData,
	private val getLastRunTimestamp: ((String) -> Long?)? = null
) : Div(classes = setOf("blocks-card-view")) {

	private var nodes: List<Node> = emptyList()

	var selectedNode: Node? = null
		private set

	// Keep track of the currently selected card element so we can
	// toggle selection styles without re-rendering all cards.
	private var selectedCardElem: Div? = null

	val contextMenuElem = Div(classes = setOf("card-context-menu"))

	var onSelectionChanged: ((Node?) -> Unit)? = null

	private val searchBox = TextInput(classes = setOf("search-box")).apply {
		placeholder = "Search blocks..."
	}

	private val typeSelect = SelectInput(
		options = listOf("" to "All Types"),
		classes = setOf("type-filter")
	)

	private val stateSelect = SelectInput(
		options = listOf(
			"" to "All States",
			"changed" to "Changed",
			"waiting" to "Waiting",
			"running" to "Running",
			"ended" to "Ended"
		),
		classes = setOf("state-filter")
	)

	private val sortSelect = SelectInput(
		options = listOf(
			"number" to "By Number",
			"name" to "By Name",
			"type" to "By Type",
			"status" to "By Status",
			"lastrun" to "By Last run"
		),
		classes = setOf("sort-order")
	).apply {
		value = "number"
	}

	private val filterCountSpan = Span(classes = setOf("search-results"))

	private val filtersBar = Div(classes = setOf("card-filters", "dock-window")).apply {
		div(classes = setOf("search-box-container")).apply {
			add(this@BlocksCardView.searchBox)
			iconStyled("fas fa-search", classes = setOf("search-box-icon"))
		}
		add(this@BlocksCardView.typeSelect)
		add(this@BlocksCardView.stateSelect)
		add(this@BlocksCardView.sortSelect)
		add(this@BlocksCardView.filterCountSpan)
	}

	private val selectionBar = Div(classes = setOf("selection"))
	private val cardsContainer = Div(classes = setOf("cards-container"))

	init {
		selectionBar.add(filtersBar)
		selectionBar.add(contextMenuElem)
		add(selectionBar)
		add(cardsContainer)

		showFilters()

		searchBox.onEvent { input = { renderCards() } }
		typeSelect.onEvent { change = { renderCards() } }
		stateSelect.onEvent { change = { renderCards() } }
		sortSelect.onEvent { change = { renderCards() } }

		cardsContainer.onClick {
			if (selectedNode != null) {
				deselect()
			}
		}
	}

	fun setNodes(newNodes: List<Node>) {
		nodes = newNodes
		val types = nodes.map { it.config.name }.distinct().sorted()
		typeSelect.options = listOf("" to "All Types") + types.map { it to it }
		renderCards()
	}

	fun select(node: Node) {
		// Called from outside (e.g., programmatic selection). Find the card for this node
		// and update selection without rebuilding all cards.
		val cardForNode = cardsContainer.getChildren()
			.filterIsInstance<Div>()
			.firstOrNull { it.getAttribute("data-job-id") == node.jobId }

		updateSelection(cardForNode, node)
	}

	fun deselect() {
		// Clear selection styles without re-rendering all cards.
		selectedCardElem?.removeCssClass("selected")
		selectedCardElem = null
		selectedNode = null
		showFilters()
		onSelectionChanged?.invoke(null)
	}

	fun refreshCards() {
		renderCards()
		if (selectedNode != null) {
			showContextMenu()
		}
	}

	private fun showFilters() {
		filtersBar.display = Display.FLEX
		contextMenuElem.display = Display.NONE
	}

	private fun showContextMenu() {
		filtersBar.display = Display.NONE
		contextMenuElem.display = Display.FLEX
	}

	/** Order for "By Status" sort: Running, Waiting, Changed, Ended, Unknown */
	private fun statusSortOrder(status: Node.Status): Int = when (status) {
		Node.Status.Running -> 0
		Node.Status.Waiting -> 1
		Node.Status.Changed -> 2
		Node.Status.Ended -> 3
		Node.Status.Unknown -> 4
	}

	private fun renderCards() {
		cardsContainer.removeAll()

		// Clear cached selected element; we'll repopulate it if the selected node
		// is still visible after filtering.
		selectedCardElem = null

		var filtered = nodes.toList()

		val typeFilter = typeSelect.value
		if (!typeFilter.isNullOrBlank()) {
			filtered = filtered.filter { it.config.name == typeFilter }
		}

		val stateFilter = stateSelect.value
		if (!stateFilter.isNullOrBlank()) {
			filtered = filtered.filter {
				when (stateFilter) {
					"changed" -> it.status == Node.Status.Changed
					"waiting" -> it.status == Node.Status.Waiting
					"running" -> it.status == Node.Status.Running
					"ended" -> it.status == Node.Status.Ended
					else -> true
				}
			}
		}

		val query = searchBox.value?.lowercase()
		if (!query.isNullOrBlank()) {
			filtered = filtered.filter {
				query in it.baseJob.numberedName.lowercase() || query in it.config.name.lowercase()
			}
		}

		filtered = when (sortSelect.value) {
			"name" -> filtered.sortedBy { it.baseJob.name.lowercase() }
			"type" -> filtered.sortedBy { it.config.name.lowercase() }
			"status" -> filtered.sortedBy { statusSortOrder(it.status) }
			"lastrun" -> filtered.sortedByDescending { getLastRunTimestamp?.invoke(it.jobId) ?: 0L }
			else -> filtered.sortedWith(
				compareBy<Node> { it.baseJob.common.jobNumber ?: Int.MAX_VALUE }.thenBy { it.baseJob.name }
			)
		}

		filterCountSpan.content = "Showing ${filtered.size} of ${nodes.size} blocks"

		for (node in filtered) {
			val job = node.baseJob
			val urlFragment = node.config.clientInfo.urlFragment
			val blockPath = urlFragment?.let {
				"/project/${project.owner.id}/${project.projectId}/$it/${job.jobId}"
			}

			val card = cardsContainer.div(classes = setOf("block-card", "dock-window")) {
				setAttribute("data-job-id", job.jobId)

				div(classes = setOf("block-card-header")) {
					iconStyled(node.type.iconClass, classes = setOf("block-type-icon"))
					span(job.numberedName, classes = setOf("block-card-title"))

					div(classes = setOf("block-card-status")) {
						when (node.status) {
							Node.Status.Unknown -> iconStyled("fas fa-spinner fa-pulse")
							Node.Status.Changed -> iconStyled("fas fa-asterisk").apply { title = "Has changes" }
							Node.Status.Waiting -> iconStyled("fas fa-clock").apply { title = "Waiting to run" }
							Node.Status.Running -> iconStyled("fas fa-cog fa-spin")
						Node.Status.Ended -> {
							if (job.common.stale || node.getUpstreamNodesIteratively().any { it.baseJob.isChanged() }) {
								iconStyled("fas fa-recycle").apply { title = "Out-of-date" }
							} else {
								iconStyled("fas fa-check").apply { title = "Up-to-date" }
							}
						}
						}
					}
				}

				div(classes = setOf("block-card-body")) {
					if (blockPath != null) {
						onClick { event ->
							event.stopPropagation()
							routing.go(blockPath)
						}
						setAttribute("title", "Click to open block view")
					}
					val thumbUrl = job.thumbnailUrl()
					if (thumbUrl != null) {
						image(thumbUrl, classes = setOf("block-card-thumbnail", dynamicImageClassName))
							.ifNonExistentUsePlaceholder(ImageSize.Small)
					} else {
						iconStyled(node.type.iconClass, classes = setOf("block-card-icon-large"))
					}
				}

				div(classes = setOf("block-card-footer")) {
					span(node.config.name, classes = setOf("block-card-type"))
					val metaLines = job.metadataLines()
					if (metaLines.isNotEmpty()) {
						for (line in metaLines) {
							div(line, classes = setOf("block-card-meta"))
						}
					}
				}
			}

			card.onClick { event ->
				event.stopPropagation()
				updateSelection(card, node)
			}

			if (node.jobId == selectedNode?.jobId) {
				card.addCssClass("selected")
				selectedCardElem = card
			}
		}
	}

	private fun updateSelection(card: Div?, node: Node) {

		// If we couldn't find the card (e.g. filtered out), just clear selection.
		if (card == null) {
			deselect()
			return
		}

		// Update visual selection
		selectedCardElem?.removeCssClass("selected")
		card.addCssClass("selected")
		selectedCardElem = card

		// Update state and notify listeners
		selectedNode = node
		showContextMenu()
		onSelectionChanged?.invoke(node)
	}

	companion object {

		private fun JobData.thumbnailUrl(): String? {
			val d = this.asDynamic()
			return (d.imageUrl as? String) ?: (d.imageURL as? String)
		}

		private fun JobData.metadataLines(): List<String> {
			val d = this.asDynamic()
			val lines = mutableListOf<String>()

			val infoString = d.jobInfoString as? String
			if (!infoString.isNullOrBlank()) {
				for (part in infoString.split(":").reversed()) {
					val trimmed = part.trim()
					if (trimmed.isNotEmpty()) lines.add(trimmed)
				}
			}

			val numMicrographs = (d.numMicrographs as? Number)?.toLong()
			if (numMicrographs != null) {
				lines.add("${numMicrographs.formatWithDigitGroupsSeparator()} micrograph(s)")
			}

			val numParticles = (d.numParticles as? Number)?.toLong()
			if (numParticles != null) {
				lines.add("${numParticles.formatWithDigitGroupsSeparator()} particle(s)")
			}

			val numTiltSeries = (d.numTiltSeries as? Number)?.toLong()
			if (numTiltSeries != null) {
				lines.add("$numTiltSeries tilt-series")
			}

			return lines
		}
	}
}
