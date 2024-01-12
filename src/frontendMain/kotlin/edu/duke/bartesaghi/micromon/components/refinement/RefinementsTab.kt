package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.HyperGallery
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.IntegratedRefinementView
import edu.duke.bartesaghi.micromon.views.PathableTab
import edu.duke.bartesaghi.micromon.views.RegisterableTab
import edu.duke.bartesaghi.micromon.views.TabRegistrar
import io.kvision.core.Display
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.image
import io.kvision.html.Image
import io.kvision.html.span
import io.kvision.utils.perc
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.img
import kotlinx.html.span


class RefinementsTab(
	val job: JobData,
	val state: IntegratedRefinementView.State,
	val imageType: ImageType
) : Div(classes = setOf("refinements-tab")), PathableTab {

	enum class Mode(val id: String, val nameSingular: String, val namePlural: String) {

		Refinement("refinement", "refinement", "refinements") {
			override suspend fun getAll(jobId: String): List<HasIDIterated> =
				Services.integratedRefinement.getRefinements(jobId)
					// sort so the newest thing is last
					.sortedBy { it.timestamp }
		},

		Bundle("bundle", "bundle", "bundles") {
			override suspend fun getAll(jobId: String): List<HasIDIterated> =
				Services.integratedRefinement.getRefinementBundles(jobId)
					// sort so the newest thing is last
					.sortedBy { it.timestamp }
		};

		abstract suspend fun getAll(jobId: String): List<HasIDIterated>

		fun name(count: Int): String =
			if (count == 1) {
				nameSingular
			} else {
				namePlural
			}
	}

	enum class ImageType(
		val title: String,
		val mode: Mode,
		val urlFragment: String,
		val sizes: List<Int>
	) : RegisterableTab {

		Particles(
			"Particles",
			Mode.Refinement,
			"particles",
			ImageSize.values().map { it.approxWidth }
		) {

			private fun url(job: JobData, target: HasIDIterated, imageSize: ImageSize): String =
				"${urlBase(job, target)}/$urlFragment/${imageSize.id}"

			override fun galleryHtml(container: FlowOrPhrasingContent, job: JobData, target: HasIDIterated) {
				container.img(src = url(job, target, ImageSize.Small))
			}

			override fun showPanel(job: JobData, target: HasIDIterated, panel: SequencePanel) {
				panel.elem.image(url(job, target, ImageSize.values()[panel.index]))
			}

			override fun resizePanel(job: JobData, target: HasIDIterated, panel: SequencePanel) {
				// update the image URL to match the new panel size
				val img = panel.elem.getChildren()
					.firstOrNull() as? Image
				img?.src = url(job, target, ImageSize.values()[panel.index])
			}

			override suspend fun loadImageSizes(job: JobData, target: HasIDIterated) =
				fetchImageSizes(url(job, target, ImageSize.Small))
		},

		Weights(
			"Exposure Weights",
			Mode.Bundle,
			"weights",
			listOf(1024, 1536, 2048)
		) {

			override fun galleryHtml(container: FlowOrPhrasingContent, job: JobData, target: HasIDIterated) {
				container.span {
					img(src = "${urlBase(job, target)}/scores") {
						width = "60%"
						height = "160"
					}
					img(src = "${urlBase(job, target)}/weights") {
						width = "40%"
						height = "160"
					}
				}
			}

			override fun showPanel(job: JobData, target: HasIDIterated, panel: SequencePanel) {
				panel.elem.span {
					image("${urlBase(job, target)}/scores") {
						width = 60.perc
					}
					image("${urlBase(job, target)}/weights") {
						width = 40.perc
					}
				}
			}

			override fun resizePanel(job: JobData, target: HasIDIterated, panel: SequencePanel) {
				// nothing to do, the svg automatically resizes
			}

			override suspend fun loadImageSizes(job: JobData, target: HasIDIterated) =
				ImageSizes(500, 160)
		},

		Scores(
			"Per-Particle Scores",
			Mode.Refinement,
			"scores",
			listOf(512, 1024, 1536)
		) {

			override fun galleryHtml(container: FlowOrPhrasingContent, job: JobData, target: HasIDIterated) {
				container.img(src = "${urlBase(job, target)}/scores") {
					width = "250"
					height = "215"
				}
			}

			override fun showPanel(job: JobData, target: HasIDIterated, panel: SequencePanel) {
				panel.elem.span {
					image("${urlBase(job, target)}/scores") {
						width = 100.perc
					}
				}
			}

			override fun resizePanel(job: JobData, target: HasIDIterated, panel: SequencePanel) {
				// nothing to do, the svg automatically resizes
			}

			override suspend fun loadImageSizes(job: JobData, target: HasIDIterated) =
				ImageSizes(250, 215)
		};

		abstract fun galleryHtml(container: FlowOrPhrasingContent, job: JobData, target: HasIDIterated)
		abstract fun showPanel(job: JobData, target: HasIDIterated, panel: SequencePanel)
		abstract fun resizePanel(job: JobData, target: HasIDIterated, panel: SequencePanel)
		abstract suspend fun loadImageSizes(job: JobData, target: HasIDIterated): ImageSizes

		fun urlBase(job: JobData, target: HasIDIterated): String =
			"/kv/refinements/${job.jobId}/${mode.id}/${target.id}"

		override fun registerRoutes(register: TabRegistrar) {

			register(urlFragment) {
				null
			}
		}
	}

	private val targets = ArrayList<HasIDIterated>()
	private var selectedIndex: Int? = null

	override var onPathChange = {}
	override var isActiveTab = false

	private val headerElem = Div(classes = setOf("header"))

	private val gallery = HyperGallery(
		targets,
		html = { target ->
			imageType.galleryHtml(this, job, target)
		},
		linker = { _, index ->
			showTarget(index)
		}
	)

	private val imagePanel = SequencePanel(
		imageType.sizes,
		onPrev = {
			// previous is newer, so higher index
			selectedIndex
				?.takeIf { it < targets.size - 1 }
				?.let { showTarget(it + 1) }
		},
		onNext = {
			// next is older, so lower index
			selectedIndex
				?.takeIf { it > 0 }
				?.let { showTarget(it - 1) }
		},
		onResize = {
			onResize()
		},
		onClose = {
			showTarget(null)
		},
		classes = setOf("image-panel")
	)

	init {

		// layout the UI
		add(headerElem)
		add(gallery)
		div(classes = setOf("particles-single-image")) {
			add(this@RefinementsTab.imagePanel)
		}
	}

	/** call after the element has been added to the DOM */
	fun load() {

		AppScope.launch {

			// load the initial set of refinements
			val loading = loading("Loading ${imageType.mode.namePlural.replaceFirstChar { it.uppercase() }} ...")
			val targets = try {
				imageType.mode.getAll(job.jobId)
			} catch (t: Throwable) {
				errorMessage(t)
				return@launch
			} finally {
				remove(loading)
			}

			this@RefinementsTab.targets.addAll(targets)
			updateHeader()

			// init hyper-gallery after loading data
			targets.firstOrNull()?.let { target ->
				gallery.loadIfNeeded { imageType.loadImageSizes(job, target) }
			}
		}
	}

	override fun path(): String =
		imageType.urlFragment

	private fun currentIteration(): Int? =
		targets
			.lastOrNull()
			?.iteration

	private fun updateHeader() {
		headerElem.content = (
			"Showing ${targets.size} ${imageType.mode.name(targets.size)}"
			+ (currentIteration()?.let { " from iteration $it" } ?: "")
		)
	}

	private fun updatePrevNext() {
		imagePanel.update(
			// previous is newer, so higher index
			selectedIndex?.let { it < targets.size - 1 } == true,
			// next is older, so lower index
			selectedIndex?.let { it > 0 } == true
		)
	}

	fun addTarget(target: HasIDIterated) {

		// if we started a new iteration, clear the old one
		if (target.iteration != currentIteration()) {
			targets.clear()
		}

		targets.add(target)
		updateHeader()
		gallery.loadIfNeeded { imageType.loadImageSizes(job, target) }
		updatePrevNext()
	}

	private fun showTarget(index: Int?) {

		// unhighlight the old image, if any
		val className = "image-selected"
		selectedIndex?.let { i ->
			gallery.elem(i)?.classList?.remove(className)
		}

		// highlight the new  image, if any
		selectedIndex = index
		selectedIndex?.let { i ->
			gallery.elem(i)?.classList?.add(className)
		}

		// show the image, or none
		val target = index
			?.let { targets.getOrNull(it) }
		if (target != null) {
			imagePanel.panelTitle = target.id
			imagePanel.elem.removeAll()
			imageType.showPanel(job, target, imagePanel)
			imagePanel.display = Display.BLOCK
		} else {
			imagePanel.display = Display.NONE
		}
		updatePrevNext()
	}

	private fun onResize() {
		val target = selectedIndex
			?.let { targets.getOrNull(it) }
			?: return
		imageType.resizePanel(job, target, imagePanel)
	}
}
