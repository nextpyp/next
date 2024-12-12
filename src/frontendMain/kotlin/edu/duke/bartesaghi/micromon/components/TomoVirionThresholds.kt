package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyPreprocessingNode
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import edu.duke.bartesaghi.micromon.pyp.tomoVirMethodOrDefault
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.onEvent
import io.kvision.html.*
import io.kvision.tabulator.*
import io.kvision.tabulator.js.Tabulator.RowComponent
import io.kvision.utils.toKotlinObj
import js.getHTMLElement
import org.w3c.dom.HTMLInputElement


class TomoVirionThresholds(
	val project: ProjectData,
	val job: JobData,
	val tiltSeries: TiltSeriesData
) : Div(classes = setOf("tomo-virion-thresholds", "spaced")) {

	companion object {

		/** squares in the thresholds image are 120x120 px */
		const val squareSize = 120

		object Keys {
			const val up = "ArrowUp"
			const val down = "ArrowDown"
			const val left = "ArrowLeft"
			const val right = "ArrowRight"
			const val num1 = "1"
			const val num2 = "2"
			const val num3 = "3"
			const val num4 = "4"
			const val num5 = "5"
			const val num6 = "6"
			const val num7 = "7"
			const val num8 = "8"
			const val num9 = "9"
			const val delete = "Delete"
		}
	}


	private var table: Tabulator<VirionPointer>? = null
	private val virionElem = Div(classes = setOf("virion"))
	private val cols = Div(classes = setOf("cols")).apply {

		// this needs a tab index for keyboard shortcuts to work properly
		tabindex = 5

		// wire up keyboard events
		onEvent {
			keyup = e@{ event ->
				
				// stupid Kotlin DSLs, always creating a damn mess ...
				val self = this@TomoVirionThresholds
				
				val virions = self.virions
					?: return@e
				
				// try to prevent the default browser behavior that scrolls the page up and down
				// or radio selection changes when handling events from radio buttons
				event.stopPropagation()
				event.preventDefault()

				fun select(threshold: Int?, all: Boolean = false) {
					self.selectRadio(threshold)
					if (all) {
						for (virionId in virions.keys) {
							self.setThreshold(virionId, threshold)
						}
					} else {
						self.currentThreshold = threshold
					}
				}

				when (event.key) {
					Keys.up -> self.advance(-1)
					Keys.down -> self.advance(1)
					Keys.left -> select(when (val t = self.currentThreshold) {
						null -> 8
						in 2 .. 8 -> t - 1
						else -> null
					})
					Keys.right -> select(when (val t = self.currentThreshold) {
						null -> 1
						in 1 .. 7 -> t + 1
						else -> null
					})
					Keys.num1 -> select(1, all=event.altKey)
					Keys.num2 -> select(2, all=event.altKey)
					Keys.num3 -> select(3, all=event.altKey)
					Keys.num4 -> select(4, all=event.altKey)
					Keys.num5 -> select(5, all=event.altKey)
					Keys.num6 -> select(6, all=event.altKey)
					Keys.num7 -> select(7, all=event.altKey)
					Keys.num8 -> select(8, all=event.altKey)
					Keys.num9,
					Keys.delete -> select(null, all=event.altKey)
				}
			}
		}
	}

	private var particlesListName: String? = null
	private var virions: Map<Int,Particle3D>? = null
	private var currentRow: RowComponent? = null
	private var thresholdsByVirionId: MutableMap<Int,Int>? = null
	private val radiosByThreshold = HashMap<Int?,Tag>()
	private val rowIndicesByVirionId = HashMap<Int,Int>()

	/**
	 * KVision's tabulator implementation has a hard time passing Kotlin objects to the JS library and back,
	 * so just give it a very simple pointer-like object instead of our actual virion object
	 */
	data class VirionPointer(val virionId: Int)

	private val VirionPointer.humanReadableId: String get() =
		// tragically, String.format is not available in js-land
		//"vir%04d".format(virionId)
		// so we have to use the various js formatting functions directly
		"vir" + virionId.toString().asDynamic().padStart(4, "0")

	/** Getting kotlin objects back from the JS library takes a little extra work */
	private val RowComponent.virionId: Int get() =
		toKotlinObj(getData(), VirionPointer::class).virionId


	suspend fun load() {

		// reset all current state
		removeAll()
		cols.removeAll()
		virionElem.removeAll()
		particlesListName = null
		virions = null

		val loading = loading("Loading virions and thresholds")
		try {

			// load the virions most recently used by pyp: either the auto list or a manually-picked list
			particlesListName = when (job) {

				// older combined preprocessing blocks had user-chosen list names
				is TomographyPreprocessingData -> {
					val args = job.args.finished
					val values = args?.values?.toArgValues(TomographyPreprocessingNode.pypArgs.get())
					values?.tomoVirMethodOrDefault
						?.particlesList?.invoke(job.jobId)
						?.let { list ->
							when (list.source) {
								ParticlesSource.User -> args.tomolist
								ParticlesSource.Pyp -> list.name
							}
						}
				}

				// newer more specialized blocks use constant list names
				is TomographySegmentationClosedData -> ParticlesList.AutoVirions

				else -> null
			}

			// load the virions and thresholds, if possible
			particlesListName?.let {
				virions = Services.particles.getParticles3D(OwnerType.Project, job.jobId, it, tiltSeries.id)
					.toMap()
				thresholdsByVirionId = Services.particles.getVirionThresholds(OwnerType.Project, job.jobId, it, tiltSeries.id)
					.thresholdsByParticleId
					.toMutableMap()
			}

		} catch (t: Throwable) {
			errorMessage(t)
			return
		} finally {
			remove(loading)
		}

		// show an empty UI if there aren't any virions
		val virions = virions
		if (virions == null) {
			div(classes = setOf("empty")) {
				content = "There are no virions to show."
			}
			return
		}

		// show the virions that did get loaded
		div("Showing ${virions.size} virions from $particlesListName", classes = setOf("header"))

		add(cols)

		table = cols.tabulator(
			options = TabulatorOptions<VirionPointer>(
				layout = Layout.FITDATA,
				columns = listOf(
					ColumnDefinition(
						"Virion",
						formatterComponentFunction = { _, _, p ->
							Span(p.humanReadableId)
						}
					),
					ColumnDefinition(
						"Threshold",
						formatterComponentFunction = { _, _, p ->
							Span(thresholdsByVirionId?.get(p.virionId)?.toString() ?: "(none)")
						}
					)
				),
				pagination = PaginationMode.LOCAL,
				height = "600px",
				rowClick = { _, row ->
					rowClick(row)
				}
			)
		).apply {
			val virionIds = virions.keys
				.sorted()
			rowIndicesByVirionId.clear()
			virionIds.forEachIndexed { i, virionId ->
				rowIndicesByVirionId[virionId] = i
			}
			setData(virionIds
				.map { VirionPointer(it) }
				.toTypedArray()
			)
		}

		cols.add(virionElem)

		// select the first virion by default, if any
		selectDefaultVirion()
	}

	fun hasSelectedVirion(): Boolean {
		return currentRow != null
	}

	fun selectDefaultVirion() {

		val jsTab = table?.jsTabulator
			?: return
		// NOTE: KVision will only initialize the js tabulator if this control is in the real DOM
		//       so we can't interact with it until then =(

		jsTab.getRowFromPosition(0, true)
			.falseToNull()
			?.let { rowClick(it) }
	}

	private fun rowClick(row: RowComponent) {

		val table = table ?: return

		// update the row selection
		currentRow?.let { table.deselectRow(it) }
		row.select()
		currentRow = row

		// load the virion thresholds in the right pane
		showVirion(row.virionId)
	}

	private fun showVirion(virionId: Int) {

		virionElem.removeAll()
		radiosByThreshold.clear()

		fun Container.radio(label: String, selected: Boolean): Tag {

			// use unwrapped HTML tags here so we can use the styles from the RadioSelection component
			// the Radio and RadioInput classes from KVision don't seem to be compatible with the existing CSS
			val radio = Tag(
				TAG.INPUT,
				classes = setOf("radio-selection-check"),
				attributes = mapOf(
					"type" to "radio",
					"name" to "threshold"
				)
			)

			// override default arrow key behavior for radio buttons
			radio.onEvent {
				keydown = { event ->
					event.preventDefault()
				}
				keyup = { event ->
					event.preventDefault()
				}
			}

			if (selected) {
				radio.setAttribute("checked", "")
			}

			label(classes = setOf("radio-selection")) {
				add(radio)
				span(label)
			}

			return radio
		}

		val currentThreshold = thresholdsByVirionId?.get(virionId)

		virionElem.div(classes = setOf("radios")) {

			// show radio buttons to select the threshold
			for (i in 1 .. 9) {

				val threshold = i.takeIf { it <= 8 }
				val radio = radio(threshold?.toString() ?: "-", threshold == currentThreshold)
				this@TomoVirionThresholds.radiosByThreshold[threshold] = radio

				// wire up events
				// NOTE: don't use onClick() on radio buttons,
				// since it prevents the browser default behavior of actually selecting the radio
				radio.onEvent {
					click = {
						this@TomoVirionThresholds.currentThreshold = threshold
					}
				}
			}
		}

		// set up an image map to mirror the radio buttons
		// not a custom tag, KVision just doesn't support image maps ... but whatever
		val imageMap = virionElem.customTag("map", attributes = mapOf("name" to "thresholds")) {

			val top = 0
			val bottom = squareSize*3

			for (i in 1 .. 9) {
				val left = squareSize*(i - 1)
				val right = squareSize*i
				val area = customTag("area", attributes = mapOf(
					"shape" to "rect",
					"coords" to "$left,$top,$right,$bottom"
				))

				val threshold = i.takeIf { it <= 8 }
				area.onEvent {
					click = {
						this@TomoVirionThresholds.selectRadio(threshold)
						this@TomoVirionThresholds.currentThreshold = threshold
					}
				}
			}
		}

		// show the thresholds image from pyp
		virionElem.image("/kv/jobs/${job.jobId}/data/${tiltSeries.id}/virionThresholds/$virionId")
			.apply {
				setAttribute("usemap", "#" + imageMap.getAttribute("name"))
			}

		// reset the keyboard focus so the shortcuts work
		// somehow the focus gets set somewhere else and the shortcuts stop working
		cols.focus()

		// show the keyboard shortcuts
		virionElem.disclosure(
			label = {
				span("Keyboard shortcuts")
			},
			disclosed = {
				ul {
					li("Up and down arrows move to different virions")
					li("Left and right arrows move to different thresholds")
					li("1-8 also pick the thresholds")
					li("Delete and 9 pick the no-threshold option")
					li("alt + 1-9 (or delete) (or ctrl + opt + 1-9 for Mac) applies the threshold to all virons in this tilt series")
				}
			}
		).apply {
			open = false
		}
	}

	private fun selectRadio(threshold: Int?) {

		val elem = radiosByThreshold[threshold]
			?.getHTMLElement()
			?: return

		// use the raw DOM here because KVision only knows about unwrapped tags
		(elem as HTMLInputElement).checked = true
	}

	private var currentThreshold: Int?
		get() {
			val virionId = currentRow?.virionId
				?: return null
			return thresholdsByVirionId?.get(virionId)
		}
		set(threshold) {
			currentRow?.let { setThreshold(it.virionId, threshold) }
		}

	private fun setThreshold(virionId: Int, threshold: Int?) {

		val particlesListName = particlesListName
			?: return

		if (threshold != null) {
			thresholdsByVirionId?.set(virionId, threshold)
		} else {
			thresholdsByVirionId?.remove(virionId)
		}

		// update the table too
		rowIndicesByVirionId[virionId]
			?.let { rowIndex ->
				table?.jsTabulator?.getRowFromPosition(rowIndex, true)
					.falseToNull()
					?.let { it.reformat() }
			}

		// and update the server
		AppScope.launch {
			Services.particles.setVirionThreshold(OwnerType.Project, job.jobId, particlesListName, tiltSeries.id, virionId, threshold)
		}
	}

	private fun advance(deltaIndex: Int) {

		val currentRow = currentRow ?: return

		val index = currentRow.getPosition(true).toInt()
		val nextIndex = index + deltaIndex

		// is the new index even in range?
		val nextRow = table?.jsTabulator?.getRowFromPosition(nextIndex, true).falseToNull()
			?: run {

				// nope, clear the current row selections
				this.currentRow = null
				table?.deselectRow(currentRow)

				showHurray()
				return
			}

		// otherwise, go to the new row
		nextRow.pageTo().then {
			rowClick(nextRow)
		}
	}

	private fun showHurray() {

		virionElem.removeAll()
		radiosByThreshold.clear()

		virionElem.div(classes = setOf("hurray")) {
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
		}
	}
}
