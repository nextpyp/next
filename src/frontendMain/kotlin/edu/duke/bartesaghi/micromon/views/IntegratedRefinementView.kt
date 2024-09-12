package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.components.refinement.*
import edu.duke.bartesaghi.micromon.diagram.nodes.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.*
import io.kvision.html.*
import io.kvision.html.div
import io.kvision.navbar.navLink


// these are all the nodes that use this integrated view
private val refinementNodes = listOf(
	SingleParticleCoarseRefinementNode,
	SingleParticleFlexibleRefinementNode,
	SingleParticleFineRefinementNode,
	SingleParticlePostprocessingNode,
	SingleParticleMaskingNode,
	TomographyCoarseRefinementNode,
	TomographyFlexibleRefinementNode,
	TomographyFineRefinementNode,
	TomographyMovieCleaningNode
)


fun Widget.onGoToIntegratedRefinement(viewport: Viewport, project: ProjectData, job: JobData) {
	onShow(IntegratedRefinementView.path(project, job)) {
		viewport.setView(IntegratedRefinementView(project, job))
	}
}


interface RegisterableTab {
	fun registerRoutes(register: TabRegistrar)
}
typealias TabRegistrar = (pathFragment: String, paramsFactory: (List<String>) -> Any?) -> Unit

interface PathableTab {
	fun path(): String
	var onPathChange: () -> Unit
	var isActiveTab: Boolean
}

private typealias ViewInitializer = (tabs: IntegratedRefinementView.LazyTabs) -> Unit
private typealias LazyTabSelector = (tabs: IntegratedRefinementView.LazyTabs) -> LazyTabPanel.LazyTab?

class IntegratedRefinementView(
	val project: ProjectData,
	val job: JobData,
	private val initializer: ViewInitializer = {},
	private val urlParams: Any? = null
) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {

			fun register(pathFragment: String, paramsFactory: ((List<String>) -> Any?)?, tabSelector: LazyTabSelector?) {
				for (node in refinementNodes) {
					routing.registerParamsList("^/project/($urlToken)/($urlToken)/${node.urlFragmentOrThrow}/($urlToken)/$pathFragment$") reg@{ params ->

						// get the params common to all tabs
						val userId = params[0]
						val projectId = params[1]
						val jobId = params[2]

						AppScope.launch {

							// load the project data
							val project = try {
								Services.projects.get(userId, projectId)
							} catch (t: Throwable) {
								viewport.setView(ErrorView(t))
								return@launch
							}

							// get the tab url params, if any
							val urlParams = paramsFactory
								?.invoke(params.slice(4 until params.size))

							viewport.setView(IntegratedRefinementView(
								project = project,
								job = node.getJob(jobId),
								urlParams = urlParams,
								initializer = { tabs ->
									// show the tab referenced by this URL
									tabSelector?.invoke(tabs)?.show()
								}
							))
						}
					}
				}
			}

			// listen to a base url, with no extra parameters
			register("", null, null)

			// also listen to urls for each tab
			val registrationList = listOf<Pair<RegisterableTab,LazyTabSelector>>(
				MapsTab to { it.maps },
				ClassesTab to { it.classes },
				RefinementsTab.ImageType.Particles to { it.particles },
				RefinementsTab.ImageType.Weights to { it.scores },
				RefinementsTab.ImageType.Scores to { it.weights },
				ThreeDeeTab to { it.threeDee },
				MetadataTab to { it.metadata }
			)
			for ((tab, tabSelector) in registrationList) {
				tab.registerRoutes { pathFragment, paramsFactory ->
					register(pathFragment, paramsFactory, tabSelector)
				}
			}
		}

		fun path(project: ProjectData, job: JobData): String {
			return "/project/${project.owner.id}/${project.projectId}/${job.clientInfo.urlFragmentOrThrow}/${job.jobId}"
		}

		fun go(viewport: Viewport, project: ProjectData, job: JobData) {
			routing.show(path(project, job))
			viewport.setView(IntegratedRefinementView(project, job))
		}
	}


	/**
	 * Mutable state for the view,
	 * shared by all the tabs
	 */
	inner class State(initialReconstructions: List<ReconstructionData>) {

		val reconstructions = Reconstructions()
			.apply {
				initialReconstructions.forEach { add(it) }
			}

		val iterationNav = BigListNav(
			reconstructions.iterations,
			initialIndex = reconstructions.iterations.size
				.takeIf { it > 0 }
				?.let { it - 1 },
			initialLive = true,
			has100 = false
		).apply {
			// don't label the nav with the indices, use the iteration numbers themselves
			labeler = { iteration -> iteration.toString() }
		}

		var currentIteration: Int?
			get() = iterationNav.currentIndex
				?.let { reconstructions.iterations[it] }
			set(value) {
				val index = reconstructions.iterations
					.indexOfFirst { it == value }
					.takeIf { it >= 0 }
					?: return
				iterationNav.showItem(index, false)
			}

		fun addReconstruction(reconstruction: ReconstructionData) {

			val isNewIteration = !reconstructions.hasIteration(reconstruction.iteration)

			// first add the reconstruction itself
			reconstructions.add(reconstruction)

			// then update the navs if we added a new iteration
			if (isNewIteration) {
				iterationNav.newItem()
			}
		}
	}

	private var state: State? = null

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "refinements-view"))

	class LazyTabs(
		val panel: LazyTabPanel,
		val maps: LazyTabPanel.LazyTab,
		val classes: LazyTabPanel.LazyTab?,
		val particles: LazyTabPanel.LazyTab?,
		val scores: LazyTabPanel.LazyTab?,
		val weights: LazyTabPanel.LazyTab?,
		val threeDee: LazyTabPanel.LazyTab,
		val classesMovie: LazyTabPanel.LazyTab?,
		val metadata: LazyTabPanel.LazyTab?
	)

	private var lazyTabs: LazyTabs? = null
	private var mapTab: MapTab? = null
	private var mapsTab: MapsTab? = null
	private var classesTab: ClassesTab? = null
	private var particlesTab: RefinementsTab? = null
	private var scoresTab: RefinementsTab? = null
	private var weightsTab: RefinementsTab? = null
	private var threeDeeTab: ThreeDeeTab? = null
	private var classesMovieTab: ClassesMovieTab? = null
	private var metadataTab: MetadataTab? = null

	private var connector: WebsocketConnector? = null


	override fun init(viewport: Viewport) {

		val nodeInfo = job.clientInfo

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = nodeInfo.type.iconClass)
					.onGoToIntegratedRefinement(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the 3D reconstructions
			val loadingElem = elem.loading("Fetching 3D reconstructions ...")
			val reconstructions: MutableList<ReconstructionData> = try {
				delayAtLeast(200) {
					Services.integratedRefinement.getReconstructions(job.jobId)
						.sortedBy { it.timestamp }
						.toMutableList()
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			// initialize the shared state
			val state = State(reconstructions)
			this@IntegratedRefinementView.state = state

			// open the websocket connection to listen for server-side updates
			val connector = WebsocketConnector(RealTimeServices.reconstruction) { signaler, input, output ->

				// tell the server we want to listen to this session
				output.send(RealTimeC2S.ListenToReconstruction(job.jobId).toJson())

				signaler.connected()

				// wait for responses from the server
				for (msgstr in input) {
					when (val msg = RealTimeS2C.fromJson(msgstr)) {

						is RealTimeS2C.UpdatedReconstruction -> {
							state.addReconstruction(msg.reconstruction)
							// HACKHACK: if iteration watch mode is enabled,
							// also force the maps tab to show this reconstruction now
							if (state.iterationNav.live) {
								mapsTab?.show(msg.reconstruction)
							} else {
								mapsTab?.update()
							}
							mapTab?.update()
						}

						is RealTimeS2C.UpdatedRefinement -> {
							particlesTab?.addTarget(msg.refinement)
							scoresTab?.addTarget(msg.refinement)
						}

						is RealTimeS2C.UpdatedRefinementBundle -> {
							weightsTab?.addTarget(msg.refinementBundle)
						}

						else -> Unit
					}
				}
			}
			this@IntegratedRefinementView.connector = connector
			elem.add(WebsocketControl(connector))
			connector.connect()

			// make the tabs panel
			val tabsPanel = LazyTabPanel()
			tabsPanel.persistence = Storage::integratedRefinementTabIndex

			// create all the tabs, lazily
			val tabs = mutableListOf<PathableTab>()

			// NOTE: don't inline this, it apparently triggers a compiler bug
			fun <T> T.addAndTrack(lazyTab: LazyTabPanel.LazyTab): T
				where T: PathableTab,
					  T: Component
			{
				tabs.add(this)
				lazyTab.elem.add(this)
				lazyTab.onActivate = {
					updateUrlPath(path())

					// update tab activity
					for (tab in tabs) {
						tab.isActiveTab = tab === this@addAndTrack
					}
				}
				onPathChange = {
					updateUrlPath(path())
				}
				return this
			}

			// these blocks only create a single reconstruction,
			// so we don't need to pay attention to iterations and classes
			val singleMode = nodeInfo in listOf(
				SingleParticlePostprocessingNode,
				SingleParticleMaskingNode
			)

			val mapsLazyTab = tabsPanel.addTab("Reconstruction", "fas fa-desktop") { lazyTab ->
				if (singleMode) {
					mapTab = MapTab(job, state, nodeInfo == SingleParticlePostprocessingNode)
						.addAndTrack(lazyTab)
				} else {
					val showPerParticleScores = nodeInfo in listOf(
						TomographyCoarseRefinementNode,
						TomographyFineRefinementNode,
						TomographyFlexibleRefinementNode,
						TomographyMovieCleaningNode
					)
					mapsTab = MapsTab(job, state, urlParams as? MapsTab.UrlParams?, showPerParticleScores)
						.addAndTrack(lazyTab)
				}
			}

			val classesLazyTab = if (nodeInfo in listOf(
					SingleParticleCoarseRefinementNode,
					SingleParticleFineRefinementNode,
					TomographyCoarseRefinementNode,
					TomographyFineRefinementNode,
					TomographyMovieCleaningNode
				) && state.reconstructions.maxNumClasses() > 1) {
				tabsPanel.addTab("Class View", "fas fa-image") { lazyTab ->
					classesTab = ClassesTab(this@IntegratedRefinementView, job, state, urlParams as? ClassesTab.UrlParams?)
						.addAndTrack(lazyTab)
				}
			} else {
				null
			}

			val classesMovieLazyTab = if (nodeInfo in listOf(
					SingleParticleCoarseRefinementNode,
					SingleParticleFineRefinementNode,
					TomographyCoarseRefinementNode,
					TomographyFineRefinementNode,
					TomographyMovieCleaningNode
				) && state.reconstructions.maxNumClasses() > 1) {
					tabsPanel.addTab("Classes Movie", "fas fa-film") { lazyTab ->
						lazyTab.elem.div {
							classesMovieTab = ClassesMovieTab(job, state)
								.addAndTrack(lazyTab)
						}
					}
				} else {
				null
			}

			val particlesLazyTab = if (nodeInfo in listOf(
					SingleParticleFlexibleRefinementNode,
					TomographyFlexibleRefinementNode
				)) {
				tabsPanel.addTab("Particle View", "fas fa-grip-horizontal") { lazyTab ->
					particlesTab = RefinementsTab(job, state, RefinementsTab.ImageType.Particles)
						.addAndTrack(lazyTab)
						.also {
							it.load()
						}
				}
			} else {
				null
			}

			val scoresLazyTab = if (nodeInfo in listOf(
					TomographyFineRefinementNode,
					TomographyMovieCleaningNode
				)) {
				tabsPanel.addTab("Per-particle Scores", "fas fa-shart-area") { lazyTab ->
					scoresTab = RefinementsTab(job, state, RefinementsTab.ImageType.Scores)
						.addAndTrack(lazyTab)
						.also {
							it.load()
						}
				}
			} else {
				null
			}

			val weightsLazyTab = if (nodeInfo in listOf(
					SingleParticleFlexibleRefinementNode,
					TomographyCoarseRefinementNode,
					TomographyFlexibleRefinementNode,
					TomographyFineRefinementNode,
					TomographyMovieCleaningNode
				)) {
				tabsPanel.addTab("Exposure Weights", "fas fa-sort-amount-up") { lazyTab ->
					weightsTab = RefinementsTab(job, state, RefinementsTab.ImageType.Weights)
						.addAndTrack(lazyTab)
						.also {
							it.load()
						}
				}
			} else {
				null
			}

			val threeDeeLazyTab = tabsPanel.addTab("3D View", "fas fa-cube") { lazyTab ->
				lazyTab.elem.div {
					threeDeeTab = ThreeDeeTab(state.reconstructions.all(), job)
						.addAndTrack(lazyTab)
				}
			}

			val metadataLazyTab = if (nodeInfo in listOf(
					// TODO: update this block filter list
					TomographyCoarseRefinementNode,
					TomographyFlexibleRefinementNode,
					TomographyFineRefinementNode,
					TomographyMovieCleaningNode
				)) {
				tabsPanel.addTab("Metadata", "fas fa-database") { lazyTab ->
					metadataTab = MetadataTab(job)
						.addAndTrack(lazyTab)
				}
			} else {
				null
			}

			// show and activate the tabs
			elem.add(tabsPanel)
			tabsPanel.activateDefaultTab()

			val lazyTabs = LazyTabs(
				tabsPanel,
				mapsLazyTab,
				classesLazyTab,
				particlesLazyTab,
				scoresLazyTab,
				weightsLazyTab,
				threeDeeLazyTab,
				classesMovieLazyTab,
				metadataLazyTab
			)
			this@IntegratedRefinementView.lazyTabs = lazyTabs

			// run the initializer now, to e.g. apply any settings from the URL
			initializer(lazyTabs)
		}
	}

	private fun updateUrlPath(pathFragment: String?) {

		var path = path(project, job)
		if (pathFragment != null) {
			path += "/$pathFragment"
		}

		routing.show(path)
	}

	/** Shows a map for the specified class on the current iteration */
	fun showMap(classNum: Int) {
		lazyTabs?.maps?.show()
		mapsTab?.showClass(classNum)
	}

	override fun close() {
		connector?.disconnect()
	}
}
