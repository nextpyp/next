package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.forms.enableClickIf
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.Position
import io.kvision.core.StringPair
import io.kvision.core.onEvent
import io.kvision.form.check.RadioGroup
import io.kvision.form.check.radioGroup
import io.kvision.form.select.SimpleSelect
import io.kvision.form.select.simpleSelect
import io.kvision.form.text.TextInput
import io.kvision.html.*
import io.kvision.modal.Confirm
import io.kvision.modal.Modal
import io.kvision.tabulator.*
import io.kvision.tabulator.js.Tabulator.RowComponent
import io.kvision.toast.Toast
import io.kvision.toast.ToastMethod
import io.kvision.toast.ToastOptions
import io.kvision.toast.ToastPosition
import io.kvision.utils.px
import io.kvision.utils.toKotlinObj
import js.getHTMLElement
import js.nouislider.noUiSlider
import kotlinext.js.jsObject


class FilterTable<T:PreprocessingData>(
	val datumLabel: String,
	val data: List<T>,
	val dataProps: List<PreprocessingDataProperty>,
	val writable: Boolean,
	val showDetail: (Container, Int, T) -> Unit,
	val filterer: Filterer? = null
) : Div(classes = setOf("filter-table")) {

	companion object {

		object Keys {
			const val include = "c"
			const val exclude = "x"
			const val up = "ArrowUp"
			const val down = "ArrowDown"
		}
	}

	class Filterer(
		val save: suspend (filter: PreprocessingFilter) -> Unit,
		val delete: suspend (name: String) -> Unit,
		val names: suspend () -> List<String>,
		val get: suspend (name: String) -> PreprocessingFilter,
		val export: ((name: String) -> Unit)? = null
	)

	inner class Elems(
		val table: Tabulator<DatumPointer>,
		val includeButton: Button,
		val excludeButton: Button,
		val directionRadio: RadioGroup,
		val viewpaneContent: Div,
		val prop: Div,
		val filters: Div,
		val filterCount: Span,
		val filterNameText: TextInput,
		val filterDeleteButton: Button,
		val filterExportButton: Button
	)

	/**
	 * Once again, KVision is having trouble translating Kotlin object to plain js Objects,
	 * and vice versa. It worked for a long time, and now all of a sudden KVision is complaining
	 * about not being able to write to read-only properties. Maybe the browser isn't allowing
	 * it all of a sudden? Either way, we'll have to avoid the problem entirely by only giving
	 * KVision very simple classes to serialize, but still have a way to reference the original class instance.
	 * Like a pointer.
	 */
	class DatumPointer(val i: Int)

	private var elems: Elems? = null
	private var currentFilter: PreprocessingFilter? = null
	private val emptyFiltersMessage = Div("No filters added yet", classes = setOf("empty"))
	private val excludedDatumIds = HashSet<String>()
	private val excludedChecks = HashMap<String,ReverseCheck>()
	private var currentRow: RowComponent? = null

	private inner class FilterElem(initialRange: PreprocessingPropRange? = null) : Div(classes = setOf("filter")) {

		val table = this@FilterTable

		// add to the container first, so we can use the slider
		init {
			val elems = table.elems
			if (elems != null) {
				elems.filters.remove(table.emptyFiltersMessage)
				elems.filters.add(this)
			}
		}

		// use a select box to pick the field
		val propSelect = simpleSelect(
			options = table.dataProps
				.map { it.id to it.label },
			value = initialRange?.propId
				?: table.dataProps.first().id
		)

		val prop: PreprocessingDataProperty get() = table.dataProps
			.find { it.id == propSelect.value!! }
			?: throw NoSuchElementException("no datum property with id = ${propSelect.value}")

		// add a delete button
		init {
			button("", icon = "fas fa-trash").onClick {
				remove()
			}
		}

		private fun getRange(): Pair<Double,Double> {
			val numbers = table.data.mapNotNull(prop.formatter.selector)
			val min = numbers.minOrNull() ?: 0.0
			val max = (numbers.maxOrNull() ?: min)
				.let { max ->
					// min and max can't be the same, or the slider widget will complain
					if (max <= min) {
						// so make them arbitrarily different
						min + 1
					} else {
						max
					}
				}
			return min to max
		}

		// use a slider to get the value range
		val slider = div(classes = setOf("slider")).run {
			val filter = this@FilterElem
			noUiSlider(jsObject {
				val (min, max) = filter.getRange()
				range = jsObject {
					this.min = min
					this.max = max
				}
				connect = true
				start = initialRange
					?.let { arrayOf(it.min, it.max) }
					?: arrayOf(min, max)
				tooltips = arrayOf(filter.prop.formatter.sliderFormatter, filter.prop.formatter.sliderFormatter)
			})
		}

		init {

			// reset the slider range when the number selector changes
			propSelect.onEvent {
				change = {
					val (min, max) = getRange()
					slider.updateOptions(jsObject {
						range = jsObject {
							this.min = min
							this.max = max
						}
						tooltips = arrayOf(prop.formatter.sliderFormatter, prop.formatter.sliderFormatter)
					})
					slider.setMulti(arrayOf(min, max))
				}
			}
		}

		fun makeFilter(): (Pair<T,Int>) -> Boolean {

			val min = slider.getMulti()[0].toDouble()
			val max = slider.getMulti()[1].toDouble()

			return filter@{ (datum, _) ->
				val value = prop.formatter.selector(datum) ?: return@filter false
				return@filter value in min .. max
			}
		}

		fun makePropRange() = PreprocessingPropRange(
			propId = prop.id,
			min = slider.getMulti()[0].toDouble(),
			max = slider.getMulti()[1].toDouble()
		)

		fun remove() {

			val elems = this@FilterTable.elems ?: return

			elems.filters.remove(this@FilterElem)

			// put the empty message back, if needed
			if (elems.filters.getChildren().isEmpty()) {
				elems.filters.add(this@FilterTable.emptyFiltersMessage)
			}
		}
	}

	private fun filterText(numMatched: Int) =
		"$numMatched matches"

	fun load() {

		div("Filters", classes = setOf("filters-title"))

		// add controls to load/save filters
		val filterNameText = TextInput(classes = setOf("name")).apply {
			placeholder = "Name your filters"
			onEvent {
				focus = {
					elems?.filterNameText?.removeCssClass("filter-name-error")
				}
			}
		}

		val saveButton = Button("Save", icon = "fas fa-cloud-upload-alt").apply {
			enableClickIf(writable) {
				saveFilter()
			}
		}

		val deleteButton = Button("Delete", icon = "fas fa-trash").apply {
			enabled = false
			if (writable) {
				onClick {
					deleteFilter()
				}
			}
		}

		val loadButton = Button("Load", icon = "fas fa-cloud-download-alt").apply {
			onClick {
				showFilters()
			}
		}

		val exportButton = Button("Export", icon = "fas fa-file-export").apply {
			enabled = false
			if (writable) {
				onClick {
					exportFilter()
				}
			}
		}

		// layout the controls
		val filterer = filterer
		if (filterer != null) {
			div(classes = setOf("filters-load-save")) {
				add(filterNameText)
				add(saveButton)
				add(deleteButton)
				add(loadButton)
				if (filterer.export != null) {
					add(exportButton)
				}
			}
		}

		// make a place to put the UI for individual filters
		val filtersElem = div(classes = setOf("filters")) {
			add(this@FilterTable.emptyFiltersMessage)
		}

		// button to add a new filter
		val addButton = Button("Add", icon = "fas fa-plus").onClick {
			FilterElem()
		}

		// button to apply the existing filters
		val applyFiltersButton = Button("Apply filters", icon = "fas fa-filter")

		val filterCountElem = Span(filterText(data.size), classes = setOf("matched"))

		// add the buttons in a div so we can style them
		div(classes = setOf("filter-buttons")) {
			add(addButton)
			add(applyFiltersButton)
			add(filterCountElem)
		}

		// add a dropdown to choose the number of rows to show at once
		val defaultRows = 20
		var currentRows = Storage.filterTableRows ?: defaultRows
		val rowsSelect = SimpleSelect(
			options = listOf(
				"10" to "10",
				"20" to "20",
				"50" to "50",
				"100" to "100"
			),
			value = currentRows.toString(),
			label = "Show"
		)

		// layout the rows components
		div(classes = setOf("rows")) {
			add(rowsSelect)
			span("entries at once")
		}

		// make a container for the table, with relative positioning
		val tableContainer = div().apply {
			position = Position.RELATIVE
		}

		// show the data as a table
		// FYI: http://tabulator.info/docs/4.9

		// build the columns
		val columns = mutableListOf(
			ColumnDefinition<DatumPointer>(
				datumLabel,
				formatterComponentFunction = { _, _, p ->
					val datum = data[p.i]
					Span(datum.id)
				},
				field = "id",
				headerFilter = Editor.INPUT,
				headerFilterFuncCustom = { query, _, p, _ ->
					val datum = data[p.i as Int]
					query as String in datum.id
				},
				sorterFunction = { _, _, aRow, bRow, _, _, _ ->
					aRow.datum.id.compareTo(bRow.datum.id)
				}
			)
		)

		for (prop in dataProps) {
			val formatter = PreprocessingDataPropertyFormatters[prop]
			columns.add(ColumnDefinition(
				prop.label,
				formatterComponentFunction = { _, _, p ->
					val datum = data[p.i]
					Span(formatter.selector(datum)?.let { formatter.formatter(it) } ?: "")
				},
				sorterFunction = { _, _, aRow, bRow, _, _, _ ->
					val aval = formatter.selector(aRow.datum)
					val bval = formatter.selector(bRow.datum)
					if (aval != null && bval != null) {
						aval.compareTo(bval)
					} else if (aval != null) {
						-1
					} else if (bval != null) {
						1
					} else {
						0
					}
				}
			))
		}

		// add a checkbox to exclude individual data
		columns.add(ColumnDefinition(
			"Exclude",
			formatterComponentFunction = { _, _, p ->
				val datum = data[p.i]

				// read the current choice for this datum
				val initialState = if (datum.id in excludedDatumIds) {
					ReverseCheck.State.Rejected
				} else {
					ReverseCheck.State.Ignored
				}

				ReverseCheck(initialState).apply {
					this@FilterTable.excludedChecks[datum.id] = this
					onEvent {
						change = {
							this@FilterTable.updateExcluded(datum, state)
						}
					}
				}
			},
			headerSort = false
		))

		val columnsElem = tableContainer.div(classes = setOf("columns"))
		val table = columnsElem.tabulator(
			options = TabulatorOptions(
				layout = Layout.FITDATA,
				columns = columns,
				pagination = PaginationMode.LOCAL,
				paginationSize = currentRows,
				rowClick = { _, row ->
					rowClick(row)
				}
			)
		)
		table.setData(data.indices.map { DatumPointer(it) }.toTypedArray())

		// show table selections in a viewpane
		val viewpaneContent = Div()

		val includeButton = Button("Include (${Keys.include})").apply {
			enabled = false
			if (writable) {
				onClick {
					processCurrent(ReverseCheck.State.Ignored)
				}
			}
		}
		val excludeButton = Button("Exclude (${Keys.exclude})").apply {
			enabled = false
			if (writable) {
				onClick {
					processCurrent(ReverseCheck.State.Rejected)
				}
			}
		}
		val directionRadio = RadioGroup(options = listOf(
			"next" to "Next",
			"prev" to "Previous"
		)).apply {
			value = "next"
		}

		// wire up the keyboard shortcuts
		tableContainer.onEvent {
			keyup = { event ->

				when (event.key) {
					Keys.include -> processCurrent(ReverseCheck.State.Ignored)
					Keys.exclude -> processCurrent(ReverseCheck.State.Rejected)
					Keys.up -> advance(-1)
					Keys.down -> advance(1)
				}

				// try to prevent the default browser behavior that scrolls the page up and down
				event.stopPropagation()
				event.preventDefault()
			}
		}

		// lay out the viewpane
		columnsElem.div(classes = setOf("viewpane")) {

			if (this@FilterTable.filterer != null) {
				div(classes = setOf("controls")) {
					div {
						add(includeButton)
						add(excludeButton)
					}
					span("and then move to the")
					add(directionRadio)
					span("entry")
				}
			}

			add(viewpaneContent)
		}

		/*
			Whenever we refresh the data in the table, the browser often jumps to a new scroll position.
			This is caused by the table removing all its HTML elements during the refresh, and temporarily
			taking up much less vertical space. But then we then table re-fills with HTML elements, the
			browser isn't smart enough to know that we want to go back to the previous scroll position.
			However, we can trick the browser into not messing up the scroll position in the first place
			by "propping" open the page size with another HTML element that's relatively positioned.
		*/
		val prop = tableContainer.div().apply {
			position = Position.ABSOLUTE
		}

		elems = Elems(
			table,
			includeButton,
			excludeButton,
			directionRadio,
			viewpaneContent,
			prop,
			filtersElem,
			filterCountElem,
			filterNameText,
			deleteButton,
			exportButton
		)

		setViewNone()

		// set the inital (empty) filter
		updateFilter(emptyList())

		// wire up events
		rowsSelect.onEvent {
			change = {
				currentRows = rowsSelect.value?.toInt() ?: currentRows
				table.setPageSize(currentRows)
				Storage.filterTableRows = currentRows
			}
		}
		applyFiltersButton.onClick {
			update()
		}
	}

	fun update() {
		updateFilter(filters().map { it.makeFilter() })
	}

	private fun setViewNone() {

		val elems = elems ?: return

		elems.viewpaneContent.removeAll()

		// start with an empty message
		elems.viewpaneContent.div(classes = setOf("empty")) {
			content = "Nothing selected yet"
		}
	}

	/**
	 * Getting kotlin objects back from the js library takes a little extra work
	 */
	private val RowComponent.index: Int get() {
		val p = toKotlinObj(getData(), DatumPointer::class)
		return p.i
	}

	private val RowComponent.datum: T get() =
		data[index]

	private fun rowClick(row: RowComponent) {

		val elems = elems ?: return

		// ignore clicks for the same row
		if (currentRow === row) {
			return
		}

		// update the row selection
		currentRow?.let { elems.table.deselectRow(it) }
		row.select()
		currentRow = row

		// load the datum info in the right pane
		setView(row.index)
	}

	private fun setView(index: Int) {

		val elems = elems ?: return

		elems.viewpaneContent.removeAll()

		showDetail(elems.viewpaneContent, index, data[index])

		// enable the controls
		elems.includeButton.enabled = writable
		elems.excludeButton.enabled = writable
	}

	private fun processCurrent(state: ReverseCheck.State) {

		val elems = elems ?: return
		val currentRow = currentRow ?: return

		// actually update the filter
		excludedChecks.get(currentRow.datum.id)?.state = state

		// advance!
		advance(when (elems.directionRadio.value) {
			"next" -> +1
			"prev" -> -1
			else -> return
		})
	}

	private fun advance(deltaIndex: Int) {

		val elems = elems ?: return
		val currentRow = currentRow ?: return

		val index = currentRow.getPosition(true).toInt()
		val nextIndex = index + deltaIndex

		// is the new index even in range?
		val nextRow = elems.table.jsTabulator?.getRowFromPosition(nextIndex, true).falseToNull()
			?: run {

				// nope, clear the current row selections
				this.currentRow = null
				elems.table.deselectRow(currentRow)

				setViewHurray()
				return
			}

		// otherwise, go to the new row
		nextRow.pageTo().then {
			rowClick(nextRow)
		}
	}

	private fun updateExcluded(datum: T, state: ReverseCheck.State) {
		when (state) {
			ReverseCheck.State.Ignored -> excludedDatumIds.remove(datum.id)
			ReverseCheck.State.Rejected -> excludedDatumIds.add(datum.id)
		}
	}

	private fun setViewHurray() {

		val elems = elems ?: return

		// disable the controls
		elems.includeButton.enabled = false
		elems.excludeButton.enabled = false

		elems.viewpaneContent.removeAll()

		elems.viewpaneContent.div(classes = setOf("hurray")) {
			span(classes = setOf("empty")) {
				icon("fas fa-star")
				span("You've reached the end. Congratulations!")
				icon("fas fa-star")
			}
			div(classes = setOf("trophy")) {
				icon("fas fa-award")
				icon("fas fa-trophy")
				icon("fas fa-medal")
			}
			span(classes = setOf("empty")) {
				content = "Don't forget to save your filter."
			}
		}
	}

	private fun updateFilter(filters: List<(Pair<T,Int>) -> Boolean>) {

		val elems = elems ?: return

		// filter the data
		var filtered = data.zip(data.indices)
		for (filter in filters) {
			filtered = filtered.filter(filter)
		}

		// sort so the most recent data are at the top by default
		filtered = filtered
			.sortedBy { (datum, _) -> datum.timestamp }
			.reversed()

		// update the table
		elems.table.setData(filtered.map { (_, i) -> DatumPointer(i) }.toTypedArray())

		// update count
		elems.filterCount.content = filterText(filtered.size)

		// update the prop, to save the page height when the table changes (and the scroll position)
		elems.prop.top = (elems.table.getHTMLElement()?.clientHeight ?: 100).px
	}

	private fun filters(): List<FilterElem> =
		elems?.filters
			?.getChildren()
			?.filterIsInstance<FilterElem>()
			?: emptyList()

	private fun clearFilters() {
		filters()
			.forEach { it.remove() }
	}

	private val toastOptions = ToastOptions(
		positionClass = ToastPosition.TOPLEFT,
		showMethod = ToastMethod.SLIDEDOWN,
		hideMethod = ToastMethod.SLIDEUP
	)

	private fun makeFilter(): PreprocessingFilter? {

		val elems = elems ?: return null

		// get the filter name, if any
		val name = elems.filterNameText.value ?: ""
		if (name.isBlank() || name == NoneFilterOption) {
			return null
		}

		return PreprocessingFilter(
			name,
			ranges = filters().map { it.makePropRange() },
			excludedIds = excludedDatumIds.toList()
		)
	}

	private fun saveFilter() {

		val filterer = filterer ?: return
		val elems = elems ?: return

		val filter = makeFilter()
		if (filter == null) {
			elems.filterNameText.addCssClass("filter-name-error")
			return
		}

		// set as the active filter
		currentFilter = filter
		elems.filterDeleteButton.enabled = writable
		elems.filterExportButton.enabled = writable

		AppScope.launch {
			try {
				filterer.save(filter)
				Toast.success("Filters saved!", options = toastOptions)
			} catch (t: Throwable) {
				Toast.error(t.message ?: "(unknown reason)",  "Filter save failed", options = toastOptions)
			}
		}
	}

	private fun deleteFilter() {

		val filterer = filterer ?: return
		val elems = elems ?: return

		// is a filter loaded?
		val name = currentFilter?.name
			?: return

		// confirm the deletion
		Confirm.show(text = "Really delete the filter: $name?") {

			AppScope.launch {
				try {
					filterer.delete(name)
					Toast.success("Filters deleted!", options = toastOptions)

					// remove the active filter
					currentFilter = null
					elems.filterNameText.value = null
					elems.filterDeleteButton.enabled = false
					elems.filterExportButton.enabled = false

				} catch (t: Throwable) {
					Toast.error(t.message ?: "(unknown reason)",  "Filter deletion failed", options = toastOptions)
				}
			}
		}
	}

	private fun showFilters() {

		val filterer = filterer ?: return

		// show a popup with the filters names
		val win = Modal(
			caption = "Load filters",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup")
		)

		val okButton = Button("Load")
			.apply {
				enabled = false
			}
		win.addButton(okButton)

		win.show()

		AppScope.launch {

			val loading = win.loading("Loading filters ...")
			try {

				val names = filterer.names()

				if (names.isEmpty()) {
					win.div("No filters to show", classes = setOf("empty"))
					return@launch
				}

				val radio = win.radioGroup(
					options = names.map { StringPair(it, it) }
				)

				// set the initial selection
				radio.value = currentFilter?.name ?: names.first()

				okButton.enabled = true
				okButton.onClick {
					radio.value?.let {
						win.hide()
						loadFilter(it)
					}
				}

			} catch (t: Throwable) {
				win.errorMessage(t)
			} finally {
				win.remove(loading)
			}
		}
	}

	private fun loadFilter(name: String) {

		val filterer = filterer ?: return
		val elems = elems ?: return

		AppScope.launch {

			// try to load the filter
			val filter = try {
				filterer.get(name)
			} catch (t: Throwable) {
				Toast.error(t.message ?: "(unknown reason)",  "Filter load failed", options = toastOptions)
				return@launch
			}

			clearFilters()
			excludedDatumIds.clear()
			setViewNone()

			// show the filter
			currentFilter = filter
			elems.filterNameText.value = filter.name
			elems.filterDeleteButton.enabled = writable
			elems.filterExportButton.enabled = writable
			for (range in filter.ranges) {
				FilterElem(range)
			}
			excludedDatumIds.addAll(filter.excludedIds)

			// update all the table rows, so they show the new choices from this filter
			update()
		}
	}

	private fun exportFilter() {

		val export = filterer?.export ?: return

		// is a filter loaded?
		val name = currentFilter?.name
			?: return

		// just punt to the caller on this one
		export(name)
	}
}
