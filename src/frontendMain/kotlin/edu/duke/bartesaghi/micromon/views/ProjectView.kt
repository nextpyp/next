package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.JobsMonitor
import edu.duke.bartesaghi.micromon.components.PathPopup
import edu.duke.bartesaghi.micromon.components.WebsocketControl
import edu.duke.bartesaghi.micromon.components.WorkflowImportDialog
import edu.duke.bartesaghi.micromon.components.forms.enableClickIf
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.diagram.nodes.Node
import edu.duke.bartesaghi.micromon.diagram.nodes.clientInfo
import edu.duke.bartesaghi.micromon.diagram.nodes.deduplicate
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.nodes.NodeConfigs
import edu.duke.bartesaghi.micromon.nodes.Workflow
import edu.duke.bartesaghi.micromon.pyp.ArgValues
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Block
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.dropdown.ddLink
import io.kvision.dropdown.dropDown
import io.kvision.dropdown.header
import io.kvision.dropdown.separator
import io.kvision.form.check.CheckBox
import io.kvision.form.check.CheckBoxInput
import io.kvision.html.*
import io.kvision.modal.Confirm
import io.kvision.modal.Modal
import io.kvision.navbar.navLink
import io.kvision.panel.Side
import io.kvision.panel.dockPanel
import io.kvision.panel.stackPanel
import io.kvision.toast.Toast
import io.kvision.utils.px
import io.kvision.window.window
import js.getHTMLElementOrThrow
import js.micromondiagrams.allSized
import js.values


fun Widget.onGoToProject(project: ProjectData) {
	onGo(ProjectView.path(project))
}

class ProjectView(val project: ProjectData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)$") { userId, projectId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						viewport.setView(ProjectView(project))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData) = "/project/${project.owner.id}/${project.projectId}"

		val adminInfo = ServerVal {
			Services.admin.getInfo()
		}
	}

	override val elem = Div(classes = setOf("project-view"))

	private var connector: WebsocketConnector? = null

	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
			}
		}

		// layout the root elements
		val toolbar = elem.div(classes = setOf("toolbar"))
		val toolbarLeft = toolbar.div(classes = setOf("toolbar-left"))
		toolbar.div(classes = setOf("toolbar-fill"))
		val toolbarCenter = toolbar.div(classes = setOf("toolbar-center"))
		toolbar.div(classes = setOf("toolbar-fill"))
		val toolbarRight = toolbar.div(classes = setOf("toolbar-right"))
		val dock = elem.dockPanel(classes = setOf("panel-project"))
		val panelLeft = Div(classes = setOf("panel-project-left")).also { dock.add(it, Side.CENTER) }
		val panelRight = Div(classes = setOf("panel-project-right")).also { dock.add(it, Side.RIGHT) }
		val diagram = Diagram(panelLeft).apply {
			// don't lock the model, so users can drag nodes around
			//locked = true
		}
		val panelBottom = Div(classes = setOf("panel-project-bottom")).also { panelLeft.add(it) }

		// layout the toolbar
		val importDataButton = toolbarLeft.button("Import Data", icon = "fas fa-microscope")
		val importWorkflowButton = toolbarLeft.button("Import Workflow", icon = "fas fa-project-diagram")
		toolbarLeft.add(PathPopup.button("Project filesystem location", project.path))
		val selectionDropdown = toolbarLeft.dropDown("Selection", icon = "fas fa-object-ungroup")
			.apply {
				enabled = false
			}
		val runButton = toolbarRight.button("Run")

		val driver = DiagramDriver(viewport, diagram, runButton, panelRight, panelBottom)

		// wire up toolbar events
		importDataButton.enableClickIf(project.canWrite()) {
			driver.showImportDataPopup()
		}
		importWorkflowButton.enableClickIf(project.canWrite()) {
			driver.showImportWorkflowPopup()
		}
		diagram.onSelectionChanged = { selectedNodes ->

			// rebuild the selection menu
			selectionDropdown.enabled = selectedNodes.isNotEmpty()
			selectionDropdown.removeAll()

			selectionDropdown.header("Selected: ${selectedNodes.size}")

			selectionDropdown.separator()

			selectionDropdown.ddLink("Deselect All").onClick {
				selectedNodes.forEach { it.selected = false }
				diagram.update()
				diagram.onSelectionChanged(emptyList())
			}
			selectionDropdown.separator()

			selectionDropdown.ddLink("Copy All").enableClickIf(project.canWrite()) {
				AppScope.launch {
					driver.copySelectedNodes(selectedNodes)
				}
			}
			selectionDropdown.ddLink("Delete All ...").enableClickIf(project.canWrite()) {
				driver.showDeleteForm(selectedNodes)
			}
		}

		// implement the realtime connection protocol
		val connector = WebsocketConnector(RealTimeServices.project) { signaler, input, output ->

			// tell the server we want to listen to this project
			output.sendMessage(RealTimeC2S.ListenToProject(project.owner.id, project.projectId))

			// wait for the initial status
			driver.init(input.receiveMessage<RealTimeS2C.ProjectStatus>())

			signaler.connected()

			// wait for responses from the server
			for (msg in input.messages()) {
				when (msg) {
					is RealTimeS2C.ProjectRunInit -> driver.onRunInit(msg)
					is RealTimeS2C.ProjectRunStart -> driver.onRunStart(msg)
					is RealTimeS2C.JobStart -> driver.onJobStart(msg)
					is RealTimeS2C.JobUpdate -> driver.onJobUpdate(msg)
					is RealTimeS2C.JobFinish -> driver.onJobFinish(msg)
					is RealTimeS2C.ProjectRunFinish -> driver.onRunFinish(msg)
					is RealTimeS2C.ClusterJobSubmit -> driver.onClusterJobSubmit(msg)
					is RealTimeS2C.ClusterJobStart -> driver.onClusterJobStart(msg)
					is RealTimeS2C.ClusterJobArrayStart -> driver.onClusterJobArrayStart(msg)
					is RealTimeS2C.ClusterJobArrayEnd -> driver.onClusterJobArrayFinish(msg)
					is RealTimeS2C.ClusterJobEnd -> driver.onClusterJobFinish(msg)
					else -> Unit
				}
			}
		}
		this.connector = connector
		toolbarCenter.add(WebsocketControl(connector))

		// load the jobs for this project
		AppScope.launch {

			val jobs = Services.projects.getJobs(project.owner.id, project.projectId)
				.map { JobData.deserialize(it) }

			// make nodes for the jobs
			val nodesByJobId = jobs.associate { job ->
				val node = job.clientInfo.makeNode(viewport, diagram, project, job)
				driver.wireEvents(node)
				diagram.addNode(node)
				job.jobId to node
			}

			// add links
			for (job in jobs) {
				val inNode = nodesByJobId[job.jobId] ?: continue

				for (inputData in job.clientInfo.config.inputs) {
					val inPort = inNode.getInPortOrThrow(inputData)
					val inputId = job.common.inputs[inputData.id] ?: continue

					val outNode = nodesByJobId[inputId.jobId] ?: continue
					val outData = outNode.config.getOutputOrThrow(inputId.dataId)
					val outPort = outNode.getOutPortOrThrow(outData)

					diagram.addLink(outPort.link(inPort))
				}
			}

			diagram.update()
			driver.updateRunButton()

			if (project.canWrite()) {
				// save the node positions after drag'n'drop operations
				diagram.onDragged = { moved ->
					AppScope.launch {

						// save the new position on the server
						Services.projects.positionJobs(moved.map {
							JobPosition(
								it.node.jobId,
								it.newPos.x,
								it.newPos.y
							)
						})

						// update the job data locally too
						for (m in moved) {
							m.node.baseJob.common.x = m.newPos.x
							m.node.baseJob.common.y = m.newPos.y
						}
					}
					Unit
				}
			}

			// zoom the stage to we can see all the nodes
			// but wait for the nodes to get size info first
			diagram.nodes()
				.toList()
				.map { it.model }
				.allSized { diagram.zoomOutToFitNodes() }

			connector.connect()
		}
	}

	override fun close() {

		// close the realtime connection, if any
		// this will cancel the coroutine hosting the connection by throwing a CancellationException
		connector?.disconnect()
	}

	inner class DiagramDriver(
		val viewport: Viewport,
		val diagram: Diagram,
		val runButton: Button,
		val panelRight: Container,
		val panelBottom: Container
	) {

		private val stackPanel = panelRight.stackPanel(activateLast = false)
		private val jobsMonitor = JobsMonitor(
			panelBottom,
			project,
			elem = Div(classes = setOf("jobs-monitor")).also { stackPanel.add(it) },
			getJobs = { diagram.nodes().map { it.baseJob } }
		)
		private val blocksByConfigId = HashMap<String,Block>()

		init {
			// wire up events
			updateRunButton()
			runButton.enableClickIf(project.canWrite()) {
				runAll()
			}
		}

		fun init(msg: RealTimeS2C.ProjectStatus) {

			// import the blocks
			for (block in msg.blocks) {
				blocksByConfigId[block.blockId] = block
			}

			// by default, set all the nodes to ended/changed status
			for (node in diagram.nodes()) {
				node.status = if (node.baseJob.isChanged()) {
					Node.Status.Changed
				} else {
					Node.Status.Ended
				}
			}

			// init the nodes
			for (run in msg.recentRuns) {
				for (job in run.jobs) {
					val node = diagram.findNode(job.jobId) ?: continue

					// it's possible to set the status for the same node multiple times if,
					// eg, there are multiple runs that reference this node
					// so make sure we make the most important status the current one, like running
					node.status = when (node.status) {

						Node.Status.Unknown -> {
							// shouldn't be possible, we explicitly set all the node statuses
							console.warn("node has unknown status")
							node.status
						}

						// upgrade to running,waiting if needed
						Node.Status.Ended,
						Node.Status.Changed -> {
							when (job.status) {
								RunStatus.Waiting -> Node.Status.Waiting
								RunStatus.Running -> Node.Status.Running
								else -> node.status
							}
						}

						// upgrade to running or downgrade to changed,finished if needed
						Node.Status.Waiting -> {
							when (job.status) {
								RunStatus.Running -> Node.Status.Running
								RunStatus.Canceled,
								RunStatus.Succeeded,
								RunStatus.Failed -> {
									if (node.baseJob.isChanged()) {
										Node.Status.Changed
									} else {
										Node.Status.Ended
									}
								}
								else -> node.status
							}
						}

						// running is the most important status, always keep it
						Node.Status.Running -> Node.Status.Running
					}
				}
			}
			diagram.update()

			updateRunButton()
			jobsMonitor.init(msg)
		}

		fun wireEvents(node: Node) {

			if (project.canWrite()) {
				// generic events
				node.onOutPortClick = { data ->
					showUseDataPopup(node, data)
				}
				node.onDeleteClick = {
					showDeleteForm(listOf(node))
				}
			}

			node.onEdited = {

				updateRunButton()

				// poke the downstream nodes to update their status indicators
				for (n in node.getDownstreamNodesIteratively()) {
					n.renderButtons()
				}

				// update the diagram to render any node changes
				diagram.update()
			}

			node.onShowLatestLogClick = e@{
				AppScope.launch {
					val runId = try {
						Services.projects.latestRunId(node.jobId)
					} catch (err: dynamic) {
						Toast.error(err)
						return@launch
					}
					jobsMonitor.highlightJobRun(runId, node.jobId)
				}
			}

			if (project.canWrite()) {

				// allow copying all nodes
				node.onCopyClick = e@{

					if (node.config.inputs.isNotEmpty()) {

						// find our upstream node
						val upstream = node.getUpstreams()
							.firstOrNull()
							?: return@e

						showUseDataForm(upstream.node, upstream.outData, upstream.inData, node.config, node)

					} else {

						node.config.clientInfo.showImportForm(viewport, diagram, project, node, ::addSourceNode)
					}
				}

				node.onMakeRunnableClick = {
					node.wipe(false)
				}

				node.onDeleteFilesClick = {
					Confirm(
						caption = "Delete block files and data?",
						yesCallback = {
							node.wipe(true)
						}
					).apply {
						div("Are you sure you want to delete all of the files and data for the block:")
						unorderedList {
							element(node.baseJob.numberedName)
						}
						div("The block itself and its configuration will not be deleted.")
						show()
					}
				}
			}
		}

		private fun Node.isRunnable(): Boolean =
			status in listOf(Node.Status.Changed, Node.Status.Ended)
				&& (baseJob.isChanged() || baseJob.common.stale || getUpstreamNodesIteratively().any { it.baseJob.isChanged() })
			// TODO: will this be too slow? Do we need to do something less stupid?

		private fun Node.wipe(deleteFilesAndData: Boolean) {
			AppScope.launch e@{

				// update the server
				try {
					Services.projects.wipeJob(jobId, deleteFilesAndData)
				} catch (t: Throwable) {
					Toast.error("Failed to wipe job: ${t.message ?: "(unknown error)"}")
					return@e
				}

				// update locally too
				baseJob.common.stale = true
				renderButtons()
				diagram.update()
				updateRunButton()
			}
		}

		fun updateRunButton() {
			runButton.apply {

				if (project.canWrite()) {

					// allow running if any nodes are runnable
					enabled = diagram.nodes()
						.any { it.isRunnable() }
					title = if (enabled) {
						"Ready to run pending jobs"
					} else {
						"No pending jobs to run"
					}

				} else {

					enabled = false
					title = "Can't run jobs, project is read-only"
				}

				icon = "fas fa-play"
			}
		}

		fun showImportDataPopup() {

			// show a popup with the all of the raw data buttons
			val win = Modal(
				caption = "Import Data",
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "import-data-popup")
			)

			win.batch {

				// get the importable nodes, ie nodes that have no inputs
				val nodes = NodeConfigs.nodes
					.filter { it.inputs.isEmpty() }

				// TODO: add toggle to show legacy nodes?

				// show the changed nodes
				win.h1 {
					span("Single Particle:")
					// show a button for each node
					for (node in nodes.filter { it.type == NodeConfig.NodeType.SingleParticleRawData }) {
						button(node.name, icon = node.clientInfo.type.iconClass).apply {
							disabled = !node.enabled
							onClick {
								win.hide()

								// show the node's import form
								node.clientInfo.showImportForm(viewport, diagram, project, null, ::addSourceNode)
							}
						}
					}
				}

				// show the downstream nodes
				win.h1 {
					span("Tomography:")
					// show a button for each node
					for (node in nodes.filter { it.type == NodeConfig.NodeType.TomographyRawData }) {
						button(node.name, icon = node.clientInfo.type.iconClass).apply {
							disabled = !node.enabled
							onClick {
								win.hide()

								// show the node's import form
								node.clientInfo.showImportForm(viewport, diagram, project, null, ::addSourceNode)
							}
						}
					}
				}
			}

			win.show()
		}

		private fun addSourceNode(node: Node) {

			// wire up events before doing anything to the node that would trigger a render,
			// since the render logic depends on which events are connected
			wireEvents(node)

			// add the node to the diagram
			node.status = Node.Status.Changed
			diagram.addNode(node)

			diagram.update()
			updateRunButton()

			diagram.place(node)
		}

		fun showImportWorkflowPopup() {

			// show a popup with the all of the raw data buttons
			val win = Modal(
				caption = "Import workflow",
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "import-workflows-popup", "max-height-dialog")
			)

			val loading = win.loading("Loading workflows ...")
			win.show()

			AppScope.launch {

				// load workflows from the server
				val workflows = try {
					delayAtLeast(200) {
						Services.projects.workflows()
					}
				} catch (t: Throwable) {
					win.errorMessage(t)
					return@launch
				} finally {
					win.remove(loading)
				}

				// show the workflows
				win.div(classes = setOf("workflows")) {
					if (workflows.isNotEmpty()) {
						for (workflow in workflows) {

							val importButton = Button("Import")
								.onClick {
									win.hide()
									WorkflowImportDialog(workflow).show(::addWorkflow)
								}

							div(classes = setOf("workflow")) {
								div(classes = setOf("header")) {
									h1(workflow.name)
									add(importButton)
								}
								div(classes = setOf("description")) {
									content = workflow.description
								}
							}
						}
					} else {
						div("No workflows available", classes = setOf("empty", "spaced"))
					}
				}
			}
		}

		private fun addWorkflow(workflow: Workflow, askArgValues: ArgValuesToml) = AppScope.launch {

			val nodes = HashMap<String,Node>()

			// add all the nodes in the workflow
			for (block in workflow.blocks) {

				// get node info for this block
				val inNodeConfig = NodeConfigs[block.blockId]
					?: throw NoSuchElementException("No node with id=${block.blockId} for block instance with id=${block.instanceId}")

				// build the arg values
				val argValues = ArgValues(inNodeConfig.clientInfo.pypArgs.get())
				argValues.setAll(block.argValues)
				argValues.setAll(askArgValues.toArgValues(argValues.args))

				// get the upstream node, and the data connection, if any
				data class UpstreamInfo(
					val outNode: Node,
					val outData: NodeConfig.Data,
					val inData: NodeConfig.Data,
					val jobInput: CommonJobData.DataId
				)
				val upstream = block.parentInstanceId?.let { parentInstanceId ->

					val outNode = nodes[parentInstanceId]
						?: throw Error("no block instance found with instanceId=$parentInstanceId")

					// Technically, blocks could have multiple inputs and outputs,
					// but we seem to really only ever use one of each.
					// Meaning, in practice, each block has exactly one parent that has exactly one output.
					// So there's only ever one possible output/input pair for any block,
					// and we can just pick it unambiguously without any extra configuration.
					// If that assumption ever changes, this code will have to change too.
					val outData = outNode.config.outputs
						.takeIf { it.size == 1 }
						?.firstOrNull()
						?: throw Error("Block ${outNode.config.id} has multiple outputs, unsupported for workflows")
					val inData = inNodeConfig.inputs
						.filter { it.type == outData.type }
						.takeIf { it.size == 1 }
						?.firstOrNull()
						?: throw Error("Block ${inNodeConfig.id} has multiple inputs with type=${outData.type}, unsupported for workflows")

					val jobInput = CommonJobData.DataId(
						jobId = outNode.jobId,
						dataId = outData.id
					)

					UpstreamInfo(outNode, outData, inData, jobInput)
				}

				// create the new node
				val job = inNodeConfig.clientInfo.makeJob(project, argValues.toToml(), upstream?.jobInput)
				val inNode = inNodeConfig.clientInfo.makeNode(viewport, diagram, project, job)
				nodes[block.instanceId] = inNode

				// wire up events before doing anything to the node that would trigger a render,
				// since the render logic depends on which events are connected
				wireEvents(inNode)

				// add the node to the diagram
				inNode.status = Node.Status.Changed
				diagram.addNode(inNode)

				// add a linker to the source node, if any
				if (upstream != null) {
					val outPort = upstream.outNode.getOutPortOrThrow(upstream.outData)
					val inPort = inNode.getInPortOrThrow(upstream.inData)
					diagram.addLink(outPort.link(inPort))
				}

				// automatically position the node somewhere useful (hopefully)
				diagram.place(inNode)
			}

			diagram.update()
			updateRunButton()
		}

		private fun showUseDataPopup(node: Node, data: NodeConfig.Data) {

			val win = diagram.window(
				caption = "Use Data",
				isResizable = false,
				isDraggable = false,
				closeButton = true
			).apply {

				// position the popup next to the port
				val port = node.getOutPortOrThrow(data)
				val pos = port.getBoundingBox().getTopRight()
				left = (pos.x.toInt() + port.width.toInt() + 20).px
				top = pos.y.toInt().px

				// move the window elem onto the stage, so it inherits the spatial transforms of the node elements
				getHTMLElementOrThrow().apply {
					remove()
					diagram.stageElem.appendChild(this)
				}
			}

			win.div(classes = setOf("use-data-popup")) {

				fun Container.showNodes(nodes: List<Pair<NodeConfig,List<NodeConfig.Data>>>) {
					for ((usingNode, usingInputs) in nodes) {

						// just use the first input
						val input = usingInputs.first()

						div(classes = setOf("entry")) {

							button(usingNode.name, icon = usingNode.clientInfo.type.iconClass).apply {
								disabled = !usingNode.enabled
								onClick {
									win.close()
									showUseDataForm(node, data, input, usingNode)
								}
							}

							// show a short description
							div(classes = setOf("description")) {
								content = blocksByConfigId[usingNode.configId]?.description
									?: "(no description)"
							}
						}
					}
				}

				// what nodes can use this port?
				val (legacyNodes, regularNodes, previewNodes) = NodeConfigs.findNodesUsing(data)
					.groupBy { (config, _) -> config.status }
					.let {
						listOf(
							it[NodeConfig.NodeStatus.Legacy] ?: emptyList(),
							it[NodeConfig.NodeStatus.Regular] ?: emptyList(),
							it[NodeConfig.NodeStatus.Preview] ?: emptyList()
						)
					}

				div {
					showNodes(regularNodes)
				}

				// show the legacy,preview nodes, if needed
				val legacyCheck = CheckBox(value = false, label = "Show legacy blocks")
				val legacySection = Div()
				val previewCheck = CheckBox(value = false, label = "Show preview blocks")
				val previewSection = Div()

				fun updateVisibility() {
					legacySection.visible = legacyCheck.value
					previewSection.visible = previewCheck.value
				}

				// if dev mode, also show legacy and preview blocks
				AppScope.launch {
					if (adminInfo.get().debug) {
						if (legacyNodes.isNotEmpty()) {

							div {
								add(legacyCheck)
							}
							legacyCheck.onClick {
								updateVisibility()
							}

							add(legacySection)
							legacySection.showNodes(legacyNodes)
						}

						if (previewNodes.isNotEmpty()) {
							div {
								add(previewCheck)
							}
							previewCheck.onClick {
								updateVisibility()
							}

							add(previewSection)
							previewSection.showNodes(previewNodes)
						}
					}
				}

				updateVisibility()
			}
		}

		fun showUseDataForm(outNode: Node, outData: NodeConfig.Data, inData: NodeConfig.Data, inConfig: NodeConfig, copyFrom: Node? = null) {

			val output = CommonJobData.DataId(outNode.jobId, outData.id)
			inConfig.clientInfo.showUseDataForm(viewport, diagram, project, outNode, output, copyFrom) { inNode ->

				// wire up events before doing anything to the node that would trigger a render,
				// since the render logic depends on which events are connected
				wireEvents(inNode)

				// add the node to the diagram
				inNode.status = Node.Status.Changed
				diagram.addNode(inNode)

				// add a linker to the source node
				val outPort = outNode.getOutPortOrThrow(outData)
				val inPort = inNode.getInPortOrThrow(inData)
				diagram.addLink(outPort.link(inPort))

				diagram.update()
				updateRunButton()

				// automatically position the node somewhere useful (hopefully)
				diagram.place(inNode)
			}
		}

		fun showDeleteForm(nodes: List<Node>) {

			// find all the downstream nodes too
			val downstreamNodes = nodes
				.flatMap { it.getDownstreamNodesIteratively() }
				.deduplicate()
				.filter { it !in nodes }

			// if any of the downstream nodes are running,
			// we can't delete them, so we shouldn't delete this job either

			val win = Modal(
				caption = "Delete nodes?",
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "delete-jobs-popup")
			)

			var noneRunning = true

			fun UnorderedList.showNode(node: Node) {
				if (node.status in listOf(Node.Status.Changed, Node.Status.Ended)) {
					element(node.baseJob.numberedName)
				} else {
					noneRunning = false
					element {
						span(node.baseJob.numberedName)
						span(" - ")
						span("RUNNING", classes = setOf("running-job"))
					}
				}
			}

			if (nodes.size == 1) {
				win.div("Really delete this node?")
			} else {
				win.div("Really delete these nodes?")
			}
			win.unorderedList {
				for (node in nodes) {
					showNode(node)
				}
			}

			if (downstreamNodes.isNotEmpty()) {
				win.div("The following downstream nodes will be deleted too:")
				win.unorderedList {
					for (node in downstreamNodes) {
						showNode(node)
					}
				}
			}

			if (noneRunning) {
				win.div("This action can't be undone.")
			} else {
				win.div("Running jobs can't be deleted.", classes = setOf("running-job"))
			}

			val nodesToDelete = nodes + downstreamNodes

			val buttonLabel =
				if (nodesToDelete.size == 1) {
					"Delete it"
				} else {
					"Delete them"
				}
			win.addButton(Button(buttonLabel).enableClickIf(noneRunning) {

				win.hide()

				// remove the nodes from the diagram
				for (node in nodesToDelete) {
					diagram.removeNode(node)
					for (port in node.inPorts) {
						for (link in port.getLinks().values) {
							diagram.removeLink(link)
						}
					}
				}
				diagram.update()
				updateRunButton()

				// update the jobs monitor
				nodesToDelete.forEach {
					jobsMonitor.jobDeleted(it.jobId)
				}

				// remove the node from the server too
				AppScope.launch {
					Services.projects.deleteJobs(nodesToDelete.map { it.jobId })
				}
			})

			win.show()
		}

		suspend fun copySelectedNodes(selectedNodes: List<Node>) {

			val selectedNodesLookup = selectedNodes
				.associateBy { it.jobId }

			abstract class Signal(val upstream: Node.Upstream) // would be a sealed interface, but can't use interfaces or sealed here =(
			class SignalCopy(upstream: Node.Upstream, var upstreamCopy: Node.Upstream? = null) : Signal(upstream)
			class SignalDirect(upstream: Node.Upstream) : Signal(upstream)

			// figure out which nodes are upstream of the selected nodes
			// if the upstream node is also selected, leave a signal to connect to the *copy* of the upstream node
			val signalsByJobId = HashMap<String,Signal>()
			for (node in selectedNodes) {
				val upstream = node.getUpstreams()
					.takeIf { it.size == 1 }
					?.first()
					?: continue
				signalsByJobId[node.jobId] =
					if (upstream.node.jobId in selectedNodesLookup) {
						SignalCopy(upstream)
					} else {
						SignalDirect(upstream)
					}
			}

			// also index the signals by the upstream jobId
			val signalsByUpstreamJobId = HashMap<String,MutableList<Signal>>()
			for (signal in signalsByJobId.values) {
				signalsByUpstreamJobId
					.getOrPut(signal.upstream.node.jobId) { ArrayList() }
					.add(signal)
			}

			// copy the nodes
			val copiedNodes = HashMap<String,Node>()
			val nodesToCopy = selectedNodes
				.toMutableList()
			while (nodesToCopy.isNotEmpty()) {

				// find all the nodes we can copy this round
				val nodesAndUpstreams = nodesToCopy
					.mapNotNull { node ->

						// find the upstream signal, if any
						when (val signal = signalsByJobId[node.jobId]) {

							is SignalCopy -> {
								if (signal.upstreamCopy != null) {
									node to signal.upstreamCopy
								} else {
									// skip for now, upstream node hasn't been copied yet
									return@mapNotNull null
								}
							}

							is SignalDirect -> node to signal.upstream

							// no upstream node, can copy without link
							else -> node to null
						}
					}

				if (nodesAndUpstreams.isEmpty()) {
					throw IllegalStateException("No nodes can be copied this round, this is a bug!")
				}

				for ((node, upstream) in nodesAndUpstreams) {

					// remove the node from the list so we won't try to copy it again
					nodesToCopy.remove(node)

					// create the new node
					val argValuesCopy = node.newestArgValues() ?: ""
					val jobCopy = node.config.clientInfo.makeJob(project, argValuesCopy, upstream?.outDataId())
					val nodeCopy = node.config.clientInfo.makeNode(viewport, diagram, project, jobCopy)
					copiedNodes[nodeCopy.jobId] = nodeCopy

					// if we have any downstream nodes, update their upstream signals with the newly-copied node
					signalsByUpstreamJobId[node.jobId]
						?.filterIsInstance<SignalCopy>()
						?.forEach { downstreamSignal ->
							downstreamSignal.upstreamCopy = downstreamSignal.upstream.copy(node = nodeCopy)
						}

					// wire up events before doing anything to the node that would trigger a render,
					// since the render logic depends on which events are connected
					wireEvents(nodeCopy)

					// add the node to the diagram
					nodeCopy.status = Node.Status.Changed
					diagram.addNode(nodeCopy)

					// add a linker to the source node, if any
					if (upstream != null) {
						val outPort = upstream.node.getOutPortOrThrow(upstream.outData)
						val inPort = nodeCopy.getInPortOrThrow(upstream.inData)
						diagram.addLink(outPort.link(inPort))
					}

					// place the copied node on top of the source one, but right/down a bit
					nodeCopy.pos = Node.Pos(
						x = node.x + 40.0,
						y = node.y + 40.0
					)
				}
			}

			// move the selection to the copied nodes
			selectedNodes.forEach { it.selected = false }
			copiedNodes.values.forEach { it.selected = true }
			diagram.onSelectionChanged(copiedNodes.values.toList())

			diagram.update()
			updateRunButton()

			// save all the node positions
			Services.projects.positionJobs(copiedNodes.values.map {
				JobPosition(it.jobId, it.x, it.y)
			})
		}

		private fun runAll() {
			showRunPopup(diagram.nodes().filter { it.isRunnable() })
		}

		private fun showRunPopup(nodes: List<Node>) {

			// disable the run button immediately, until we resolve the popup
			runButton.run {
				icon = "fas fa-spinner fa-pulse"
				title = "Starting to run jobs ..."
				disabled = true
			}

			// categorize the nodes
			val (changedNodes, downstreamNodes) = nodes.partition { it.baseJob.isChanged() }

			val changedSetAllButton = Button("", icon = "far fa-check-square").apply {
				title = "Select All"
			}
			val changedSetNoneButton = Button("", icon = "far fa-square").apply {
				title = "Select None"
			}
			val downstreamSetAllButton = Button("", icon = "far fa-check-square").apply {
				title = "Select All"
			}
			val downstreamSetNoneButton = Button("", icon = "far fa-square").apply {
				title = "Select None"
			}
			val validationMsg = Div(classes = setOf("validation-message"))
			val startButton = Button("")

			val win = Modal(
				caption = "Run Jobs",
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "project-run-popup")
			)
			win.show()

			AppScope.launch {

				// validate the node args
				val loading = win.loading("Validating block arguments ...")
				val nodeValid = try {
					nodes.associate { it.jobId to it.validate() }
				} catch (t: Throwable) {
					win.errorMessage(t)
					return@launch
				} finally {
					win.remove(loading)
				}

				// make checkboxes for all the nodes
				val nodeFlags = nodes.associate { it.jobId to CheckBoxInput(true) }

				val jobIds = ArrayList<String>()
				fun update() {

					jobIds.clear()
					jobIds.addAll(changedNodes
						.filter { nodeFlags[it.jobId]?.value == true }
						.map { it.jobId }
					)
					jobIds.addAll(downstreamNodes
						.filter { nodeFlags[it.jobId]?.value == true }
						.map { it.jobId }
					)

					val numInvalid = jobIds.count { !nodeValid.getValue(it) }
					if (numInvalid > 0) {
						validationMsg.content = "$numInvalid blocks have validation issues."
					} else {
						validationMsg.content = null
					}

					startButton.enabled = jobIds.isNotEmpty() && numInvalid <= 0
					startButton.text = "Start Run for ${jobIds.size} ${if (jobIds.size == 1) "block" else "blocks"}"
				}
				update()

				fun Container.showNodes(nodes: List<Node>) {
					div(classes = setOf("nodes-list")) {
						for (node in nodes.sortedBy { it.baseJob.common.jobNumber }) {
							div(classes = setOf("node-line")) {

								div(classes = setOf("node-entry")) {

									add(nodeFlags.getValue(node.jobId))
									span(classes = setOf("icon")) {
										icon(node.type.iconClass)
									}
									span(node.baseJob.numberedName)
									if (!nodeValid.getValue(node.jobId)) {
										addCssClass("invalid")
									}
								}

								div(classes = setOf("node-spacer"))

								// when the line is hovered, show a button that selects only this node
								val onlyButton = button("Only", classes = setOf("only")).apply {
									onClick {
										for ((k, v) in nodeFlags) {
											v.value = k == node.jobId
										}
										update()
									}
								}
								onEvent {
									mouseover = {
										onlyButton.addCssClass("show")
									}
									mouseout = {
										onlyButton.removeCssClass("show")
									}
								}
							}
						}
					}
				}

				win.batch {

					// show the changed nodes
					win.h1 {
						span("New or Changed blocks:")
						add(changedSetAllButton)
						add(changedSetNoneButton)
					}
					win.div {
						if (changedNodes.isNotEmpty()) {
							showNodes(changedNodes)
						} else {
							div("No blocks have been changed", classes = setOf("empty"))
						}
					}

					// show the downstream nodes
					win.h1 {
						span("Runnable blocks:")
						add(downstreamSetAllButton)
						add(downstreamSetNoneButton)
					}
					win.div {
						if (downstreamNodes.isNotEmpty()) {
							showNodes(downstreamNodes)
						} else {
							div("No other blocks are runnable", classes = setOf("empty"))
						}
					}

					win.add(validationMsg)

					win.addButton(startButton)
				}

				var started = false

				// wire up events
				nodeFlags.values.forEach {
					it.onEvent {
						change = {
							update()
						}
					}
				}
				changedSetAllButton.onClick {
					changedNodes.forEach { nodeFlags[it.jobId]?.value = true }
					update()
				}
				changedSetNoneButton.onClick {
					changedNodes.forEach { nodeFlags[it.jobId]?.value = false }
					update()
				}
				downstreamSetAllButton.onClick {
					downstreamNodes.forEach { nodeFlags[it.jobId]?.value = true }
					update()
				}
				downstreamSetNoneButton.onClick {
					downstreamNodes.forEach { nodeFlags[it.jobId]?.value = false }
					update()
				}
				startButton.onClick {

					if (jobIds.isEmpty()) {
						return@onClick
					}

					enabled = false
					text = "Starting ..."
					win.hide()

					AppScope.launch {
						try {
							Services.projects.run(project.owner.id, project.projectId, jobIds)
							started = true
						} catch (t: Throwable) {
							t.reportError()
							Toast.error(t.message ?: "(Unknown error)")
						}
					}
				}
				win.onEvent {
					hideBsModal = {

						// if we didn't start the run, put the run button back
						if (!started) {
							updateRunButton()
						}
					}
				}
			}
		}

		fun onRunInit(msg: RealTimeS2C.ProjectRunInit) {

			// set node statuses to waiting, if not already doing something
			for (jobId in msg.jobIds) {
				val node = diagram.findNode(jobId) ?: continue
				node.onRunInit()
				if (node.status != Node.Status.Running) {
					node.status = Node.Status.Waiting
				}
			}
			diagram.update()

			updateRunButton()

			jobsMonitor.initRun(msg)
		}

		fun onRunStart(msg: RealTimeS2C.ProjectRunStart) {
			jobsMonitor.startRun(msg)
		}

		fun onJobStart(msg: RealTimeS2C.JobStart) {

			// update node status
			val node = diagram.findNode(msg.jobId) ?: return
			node.status = Node.Status.Running

			// update downstream node statuses
			for (n in node.getDownstreamNodesIteratively()) {
				n.baseJob.common.stale = true
				n.renderButtons()
			}
			diagram.update()

			updateRunButton()

			jobsMonitor.startJob(msg)
		}

		fun onJobUpdate(msg: RealTimeS2C.JobUpdate) {

			// update node's job data (which will re-render the node and show new thumbnails)
			val node = diagram.findNode(msg.jobId) ?: return
			val data = msg.job()
			node.refreshImagesOnNextJobUpdate = true
			node.baseJob = data

			diagram.update()
		}

		fun onJobFinish(msg: RealTimeS2C.JobFinish) {

			// update node status
			val node = diagram.findNode(msg.jobId) ?: return
			val data = msg.job()
			node.refreshImagesOnNextJobUpdate = true
			node.baseJob = data
			node.status = Node.Status.Ended
			// TODO: change to waiting if another run/job is pending?
			diagram.update()

			updateRunButton()

			jobsMonitor.finishJob(msg)
		}

		fun onRunFinish(msg: RealTimeS2C.ProjectRunFinish) {
			jobsMonitor.finishRun(msg)
		}

		fun onClusterJobSubmit(msg: RealTimeS2C.ClusterJobSubmit) {
			jobsMonitor.submitClusterJob(msg)
		}

		fun onClusterJobStart(msg: RealTimeS2C.ClusterJobStart) {
			jobsMonitor.startClusterJob(msg)
		}

		fun onClusterJobArrayStart(msg: RealTimeS2C.ClusterJobArrayStart) {
			jobsMonitor.startClusterJobArray(msg)
		}

		fun onClusterJobArrayFinish(msg: RealTimeS2C.ClusterJobArrayEnd) {
			jobsMonitor.finishClusterJobArray(msg)
		}

		fun onClusterJobFinish(msg: RealTimeS2C.ClusterJobEnd) {
			jobsMonitor.finishClusterJob(msg)
		}
	}
}
