package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.diagram.nodes.Node
import edu.duke.bartesaghi.micromon.iconStyled
import edu.duke.bartesaghi.micromon.onGo
import edu.duke.bartesaghi.micromon.services.ProjectData
import io.kvision.core.onEvent
import io.kvision.core.Position
import io.kvision.form.select.SelectInput
import io.kvision.form.text.TextInput
import kotlinx.coroutines.delay
import io.kvision.html.*
import io.kvision.tabulator.*
import io.kvision.tabulator.js.Tabulator.RowComponent
import io.kvision.utils.toKotlinObj
import kotlin.js.Date

/**
 * Row data for one block (job) in the project blocks table.
 * Block names use the form "B??-name" where ?? is the block number; number and name are stored separately.
 */
data class BlockRow(
	val jobId: String,
	/** Block number (e.g. "1", "2"); null if not set */
	val blockNumber: String?,
	/** Block name without the "B??-" prefix */
	val blockName: String,
	val typeName: String,
	val typeIconClass: String,
	val lastRunTimestamp: Long?,
	val status: Node.Status,
	/** URL path to block view, or null if this block has no dedicated view */
	val blockPath: String?
)

/**
 * Table listing all blocks in a project, using the same Tabulator primitives as the
 * pre-processing FilterTable. Columns: Name (with link), Type, Last run, State.
 */
class BlocksTable(
	val project: ProjectData,
	val rows: List<BlockRow>
) : Div(classes = setOf("filter-table", "blocks-table")) {

	/**
	 * Pointer for Tabulator row data (same pattern as FilterTable.DatumPointer).
	 */
	class RowPointer(val i: Int)

	/** Get RowPointer from Tabulator RowComponent (same pattern as FilterTable). */
	private val RowComponent.pointer: RowPointer get() =
		toKotlinObj(getData(), RowPointer::class)

	private var tableRef: Tabulator<RowPointer>? = null

	init {
		load()
	}

	private fun load() {
		// Filter controls: same structure and classes as dashboard project filters
		val typeOptions = listOf("" to "All Types") + rows.map { it.typeName }.distinct().sorted()
			.filter { !it.equals("Time", ignoreCase = true) && !it.equals("By Time", ignoreCase = true) }
			.map { it to it }
		val stateOptions = listOf("" to "All States") + Node.Status.values().toList().map { statusLabel(it) to statusLabel(it) }

		val typeSelect = SelectInput(typeOptions, classes = setOf("sharing")).apply {
			value = ""
		}
		val stateSelect = SelectInput(stateOptions, classes = setOf("sort-order")).apply {
			value = ""
		}

		val nameSearchInput = TextInput(classes = setOf("search-box")).apply {
			placeholder = "Search blocks by name"
		}

		val filterCountSpan = Span("${rows.size} blocks", classes = setOf("search-results"))

		fun updateFilter() {
			val typeVal = typeSelect.value ?: ""
			val stateVal = stateSelect.value ?: ""
			val nameQuery = (nameSearchInput.value ?: "").trim().lowercase()
			val filtered = rows.indices.filter { i ->
				val r = rows[i]
				(typeVal == "" || r.typeName == typeVal) &&
					(stateVal == "" || statusLabel(r.status) == stateVal) &&
					(nameQuery == "" || r.blockName.lowercase().contains(nameQuery))
			}
			tableRef?.setData(filtered.map { RowPointer(it) }.toTypedArray())
			filterCountSpan.content = "${filtered.size} of ${rows.size} blocks"
		}

		// Match dashboard .selection layout: search-box-container (search + icon), then search-second-container (dropdowns + count)
		val selectionBar = div(classes = setOf("selection")).apply {
			div(classes = setOf("search-box-container")).apply {
				add(nameSearchInput)
				iconStyled("fas fa-search", classes = setOf("search-box-icon"))
			}
			div(classes = setOf("search-second-container")).apply {
				add(typeSelect)
				add(stateSelect)
				add(filterCountSpan)
			}
		}
		add(selectionBar)

		val tableContainer = div().apply {
			position = Position.RELATIVE
		}

		nameSearchInput.onEvent {
			input = { updateFilter() }
			change = { updateFilter() }
		}
		typeSelect.onEvent {
			change = { updateFilter() }
		}
		stateSelect.onEvent {
			change = { updateFilter() }
		}

		val columns = listOf(
			ColumnDefinition<RowPointer>(
				title = "Number",
				formatterComponentFunction = { _, _, p ->
					val row = rows[p.i]
					Span(row.blockNumber ?: "—")
				},
				field = "blockNumber",
				sorterFunction = { _, _, aRow, bRow, _, _, _ ->
					val a = rows[aRow.pointer.i].blockNumber?.toIntOrNull() ?: Int.MAX_VALUE
					val b = rows[bRow.pointer.i].blockNumber?.toIntOrNull() ?: Int.MAX_VALUE
					a.compareTo(b)
				}
			),
			ColumnDefinition<RowPointer>(
				title = "Type",
				formatterComponentFunction = { _, _, p ->
					val row = rows[p.i]
					Div(classes = setOf("block-type-cell")).apply {
						span(classes = setOf("icon")) {
							iconStyled(row.typeIconClass)
						}
						span(row.typeName)
					}
				},
				field = "typeName",
				sorterFunction = { _, _, aRow, bRow, _, _, _ ->
					rows[aRow.pointer.i].typeName.compareTo(rows[bRow.pointer.i].typeName)
				}
			),
			ColumnDefinition<RowPointer>(
				title = "Name",
				formatterComponentFunction = { _, _, p ->
					val row = rows[p.i]
					Div(classes = setOf("block-name-cell")).apply {
						if (row.blockPath != null) {
							link(row.blockName, classes = setOf("link")).apply {
								onGo(row.blockPath!!)
							}
						} else {
							span(row.blockName)
						}
					}
				},
				field = "blockName",
				sorterFunction = { _, _, aRow, bRow, _, _, _ ->
					rows[aRow.pointer.i].blockName.compareTo(rows[bRow.pointer.i].blockName)
				}
			),
			ColumnDefinition<RowPointer>(
				title = "Last run",
				formatterComponentFunction = { _, _, p ->
					val row = rows[p.i]
					val text = row.lastRunTimestamp
						?.let { Date(it).toLocaleString() }
						?: "—"
					Span(text)
				},
				field = "lastRun",
				sorterFunction = { _, _, aRow, bRow, _, _, _ ->
					val a = rows[aRow.pointer.i].lastRunTimestamp ?: 0L
					val b = rows[bRow.pointer.i].lastRunTimestamp ?: 0L
					a.compareTo(b)
				}
			),
			ColumnDefinition<RowPointer>(
				title = "State",
				formatterComponentFunction = { _, _, p ->
					val row = rows[p.i]
					Div(classes = setOf("block-state-cell")).apply {
						span(classes = setOf("icon")) {
							iconStyled(this@BlocksTable.statusIconClass(row.status))
						}
						span(this@BlocksTable.statusLabel(row.status))
					}
				},
				field = "status",
				sorterFunction = { _, _, aRow, bRow, _, _, _ ->
					rows[aRow.pointer.i].status.ordinal.compareTo(rows[bRow.pointer.i].status.ordinal)
				}
			),
			ColumnDefinition<RowPointer>(
				title = "Block ID",
				formatterComponentFunction = { _, _, p ->
					val row = rows[p.i]
					Span(row.jobId)
				},
				field = "jobId",
				sorterFunction = { _, _, aRow, bRow, _, _, _ ->
					rows[aRow.pointer.i].jobId.compareTo(rows[bRow.pointer.i].jobId)
				}
			)
		)

		val columnsElem = tableContainer.div(classes = setOf("columns"))
		add(tableContainer)

		// Defer Tabulator creation until this component is in the DOM (avoids "doesn't have a DOM node" when opening the Table tab)
		AppScope.launch {
			delay(0)
			tableRef = columnsElem.tabulator(
				options = TabulatorOptions(
					layout = Layout.FITDATA,
					columns = columns,
					pagination = PaginationMode.LOCAL,
					paginationSize = 20
				)
			)
			updateFilter()
		}
	}

	private fun statusIconClass(status: Node.Status): String = when (status) {
		Node.Status.Unknown -> "fas fa-spinner fa-pulse"
		Node.Status.Changed -> "fas fa-asterisk"
		Node.Status.Waiting -> "fas fa-clock"
		Node.Status.Running -> "fas fa-cog fa-spin"
		Node.Status.Ended -> "fas fa-check"
	}

	private fun statusLabel(status: Node.Status): String = when (status) {
		Node.Status.Unknown -> "Unknown"
		Node.Status.Changed -> "Changed"
		Node.Status.Waiting -> "Waiting"
		Node.Status.Running -> "Running"
		Node.Status.Ended -> "Ended"
	}
}
