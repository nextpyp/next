package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.forms.enableClickIf
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.NoneFilterOption
import edu.duke.bartesaghi.micromon.services.PreprocessingFilter
import edu.duke.bartesaghi.micromon.services.ISingleParticlePreprocessingService
import edu.duke.bartesaghi.micromon.services.PreprocessingFilterType
import edu.duke.bartesaghi.micromon.services.Services
import io.kvision.core.StringPair
import io.kvision.core.onEvent
import io.kvision.form.check.radioGroup
import io.kvision.form.text.TextInput
import io.kvision.html.*
import io.kvision.modal.Confirm
import io.kvision.modal.Modal
import io.kvision.toast.Toast
import io.kvision.toast.ToastMethod
import io.kvision.toast.ToastOptions
import io.kvision.toast.ToastPosition


/**
 * Filters section for the PrismPyp tab: Save/Load/Delete by name, then Real space and
 * Fourier space sub-tabs. Each sub-tab has its own round selectable class buttons (1..nClusters)
 * and the five panels. Each saved filter stores both class selections; downstream blocks
 * get two files: .real.classes and .fft.classes.
 */
class PrismPypFiltersSection(
	val jobId: String,
	val nClusters: Int,
	val writable: Boolean,
	val prismTrainReal: Boolean = false,
	val prismTrainFft: Boolean = false,
	classes: Set<String> = emptySet()
) : Div(classes = setOf("prismpyp-filters-section", "filter-table") + classes) {

	private val filterer = FilterTable.Filterer(
		save = { filter -> Services.blocks.saveFilter(jobId, filter, PreprocessingFilterType.PRISMPYP) },
		delete = { name -> Services.blocks.deleteFilter(jobId, name, PreprocessingFilterType.PRISMPYP) },
		names = { Services.blocks.listFilters(jobId, PreprocessingFilterType.PRISMPYP) },
		get = { name -> Services.blocks.getFilter(jobId, name, PreprocessingFilterType.PRISMPYP) }
	)

	private var filterNameText: TextInput? = null
	private var deleteButton: Button? = null
	private var currentFilter: PreprocessingFilter? = null
	private var classRadioReal: ClassesRadio? = null
	private var classRadioFourier: ClassesRadio? = null

	private val toastOptions = ToastOptions(
		positionClass = ToastPosition.TOPLEFT,
		showMethod = ToastMethod.SLIDEDOWN,
		hideMethod = ToastMethod.SLIDEUP
	)

	init {
		// Download tbz (parquet downloads are in Real space and Fourier space tabs)
		val fileDownload = FileDownloadBadge(
			filetype = ".tbz file",
			url = ISingleParticlePreprocessingService.prismpypDataPath(jobId),
			filename = "${jobId}_prismpyp.tbz",
			loader = { Services.jobs.getPrismpypTbzData(jobId) }
		)
		fileDownload.load()
		div(classes = setOf("files")) {
			span("Download:")
			add(fileDownload)
		}

		div("Filters", classes = setOf("filters-title"))

		val nameText = TextInput(classes = setOf("name")).apply nameInput@ {
			placeholder = "Name your filters"
			onEvent {
				focus = {
					this@nameInput.removeCssClass("filter-name-error")
				}
			}
		}
		filterNameText = nameText

		val saveBtn = Button("Save", icon = "fas fa-cloud-upload-alt").apply {
			enableClickIf(writable) {
				saveFilter()
			}
		}

		val delBtn = Button("Delete", icon = "fas fa-trash").apply {
			enabled = false
			if (writable) {
				onClick { deleteFilter() }
			}
		}
		deleteButton = delBtn

		val loadBtn = Button("Load", icon = "fas fa-cloud-download-alt").apply {
			onClick { showFilters() }
		}

		div(classes = setOf("filters-load-save")) {
			add(nameText)
			add(saveBtn)
			add(delBtn)
			add(loadBtn)
		}

		// Real space and Fourier space; each has class selector + Training section (Total loss, Collapse level) + Evaluation section (Clustering, UMAP, Nearest neighbors)
		flatLazyTabPanel {
			if (this@PrismPypFiltersSection.prismTrainReal) {
			addTab("Real space") { subTab ->
				val radio = ClassesRadio("Classes", canMultiSelect = true, deselectAllLabel = "Deselect all", selectAllLabel = "Select All")
				radio.count = this@PrismPypFiltersSection.nClusters
				if (this@PrismPypFiltersSection.nClusters > 0) {
					radio.checkedClasses = (this@PrismPypFiltersSection.currentFilter?.selectedClassesReal ?: radio.allClasses()).filter { it in 1..this@PrismPypFiltersSection.nClusters }
				}
				this@PrismPypFiltersSection.classRadioReal = radio
				subTab.elem.div(classes = setOf("prismpyp-class-buttons")) {
					add(radio)
				}
				val realParquetUpload = FileUpload(
					ISingleParticlePreprocessingService.prismpypUploadPath(this@PrismPypFiltersSection.jobId, "real"),
					label = ".parquet (real space)",
					filename = "micrographs.parquet",
					accept = ".parquet"
				)
				subTab.elem.div(classes = setOf("files")) {
					span("Upload:")
					add(realParquetUpload)
				}
				subTab.elem.div("Evaluation", classes = setOf("prismpyp-section-title"))
				subTab.elem.add(PrismPypPanels(
					this@PrismPypFiltersSection.jobId,
					"real",
					panels = listOf(PrismPypPanel.Clustering, PrismPypPanel.Umap, PrismPypPanel.NearestNeighbors)
				))
				subTab.elem.div("Training", classes = setOf("prismpyp-section-title"))
				subTab.elem.add(PrismPypPanels(
					this@PrismPypFiltersSection.jobId,
					"real",
					panels = listOf(PrismPypPanel.TotalLoss, PrismPypPanel.CollapseLevel)
				))
			}
			}
			if (this@PrismPypFiltersSection.prismTrainFft) {
			addTab("Fourier space") { subTab ->
				val radio = ClassesRadio("Classes", canMultiSelect = true, deselectAllLabel = "Deselect all", selectAllLabel = "Select All")
				radio.count = this@PrismPypFiltersSection.nClusters
				if (this@PrismPypFiltersSection.nClusters > 0) {
					radio.checkedClasses = (this@PrismPypFiltersSection.currentFilter?.selectedClassesFourier ?: radio.allClasses()).filter { it in 1..this@PrismPypFiltersSection.nClusters }
				}
				this@PrismPypFiltersSection.classRadioFourier = radio
				subTab.elem.div(classes = setOf("prismpyp-class-buttons")) {
					add(radio)
				}
				val fourierParquetUpload = FileUpload(
					ISingleParticlePreprocessingService.prismpypUploadPath(this@PrismPypFiltersSection.jobId, "fourier"),
					label = ".parquet (fourier space)",
					filename = "micrographs.parquet",
					accept = ".parquet"
				)
				subTab.elem.div(classes = setOf("files")) {
					span("Upload:")
					add(fourierParquetUpload)
				}
				subTab.elem.div("Evaluation", classes = setOf("prismpyp-section-title"))
				subTab.elem.add(PrismPypPanels(
					this@PrismPypFiltersSection.jobId,
					"fft",
					panels = listOf(PrismPypPanel.Clustering, PrismPypPanel.Umap, PrismPypPanel.NearestNeighbors)
				))
				subTab.elem.div("Training", classes = setOf("prismpyp-section-title"))
				subTab.elem.add(PrismPypPanels(
					this@PrismPypFiltersSection.jobId,
					"fft",
					panels = listOf(PrismPypPanel.TotalLoss, PrismPypPanel.CollapseLevel)
				))
			}
			}
		}
	}

	private fun saveFilter() {
		val name = filterNameText?.value?.trim() ?: ""
		if (name.isBlank() || name == NoneFilterOption) {
			filterNameText?.addCssClass("filter-name-error")
			return
		}
		val real = classRadioReal?.checkedClasses ?: emptyList()
		val fourier = classRadioFourier?.checkedClasses ?: emptyList()
		currentFilter = PreprocessingFilter(
			name = name,
			ranges = emptyList(),
			excludedIds = emptyList(),
			selectedClassesReal = real,
			selectedClassesFourier = fourier
		)
		deleteButton?.enabled = writable
		AppScope.launch {
			try {
				filterer.save(currentFilter!!)
				Toast.success("Filter saved!", options = toastOptions)
			} catch (t: Throwable) {
				Toast.error(t.message ?: "(unknown reason)", "Filter save failed", options = toastOptions)
			}
		}
	}

	private fun deleteFilter() {
		val name = currentFilter?.name ?: return
		Confirm.show(text = "Really delete the filter: $name?") {
			AppScope.launch {
				try {
					filterer.delete(name)
					Toast.success("Filter deleted!", options = toastOptions)
					currentFilter = null
					filterNameText?.value = null
					deleteButton?.enabled = false
				} catch (t: Throwable) {
					Toast.error(t.message ?: "(unknown reason)", "Filter deletion failed", options = toastOptions)
				}
			}
		}
	}

	private fun showFilters() {
		val win = Modal(
			caption = "Load filters",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup")
		)
		val okButton = Button("Load").apply { enabled = false }
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
		AppScope.launch {
			val filter = try {
				filterer.get(name)
			} catch (t: Throwable) {
				Toast.error(t.message ?: "(unknown reason)", "Filter load failed", options = toastOptions)
				return@launch
			}
			currentFilter = filter
			filterNameText?.value = filter.name
			deleteButton?.enabled = writable
			val real = filter.selectedClassesReal ?: filter.selectedClasses
			val fourier = filter.selectedClassesFourier ?: filter.selectedClasses
			if (real != null || fourier != null) {
				classRadioReal?.checkedClasses = (real ?: emptyList()).filter { it in 1..nClusters }
				classRadioFourier?.checkedClasses = (fourier ?: emptyList()).filter { it in 1..nClusters }
			} else {
				Toast.info("Loaded filter is micrograph-based; select classes for a new class filter.", options = toastOptions)
				classRadioReal?.checkedClasses = emptyList()
				classRadioFourier?.checkedClasses = emptyList()
			}
		}
	}

	fun update() {
		// Refresh class count if nClusters can change (e.g. after re-run)
		// Caller can recreate the component if needed
	}
}
