package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.canWrite
import edu.duke.bartesaghi.micromon.components.PathPopup
import edu.duke.bartesaghi.micromon.components.forms.focusASAP
import edu.duke.bartesaghi.micromon.components.forms.onEnter
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import edu.duke.bartesaghi.micromon.services.CommonJobData
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.ProjectData
import edu.duke.bartesaghi.micromon.services.Services
import edu.duke.bartesaghi.micromon.views.Viewport
import edu.duke.bartesaghi.micromon.views.admin.Admin
import io.kvision.form.text.textInput
import io.kvision.html.*
import io.kvision.modal.Modal
import io.kvision.panel.tabPanel
import js.micromondiagrams.MicromonDiagrams
import js.react.React
import js.react.ReactBuilder
import js.react.elems
import js.reactdiagrams.ReactDiagrams
import js.values
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement


abstract class Node(
	val viewport: Viewport,
	val diagram: Diagram,
	val type: MicromonDiagrams.NodeType,
	val config: NodeConfig,
	val project: ProjectData,
	baseJob: JobData
) {

	val jobId: String get() = baseJob.jobId

	var refreshImagesOnNextJobUpdate: Boolean = false

	var baseJob: JobData = baseJob
		set(value) {
			field = value
			model.name = value.numberedName
			renderContent(refreshImagesOnNextJobUpdate)
			refreshImagesOnNextJobUpdate = false
		}

	val model = type.model().apply {
		name = baseJob.numberedName
		inputsExclusive = config.inputsExclusive
	}

	private val inPortsByData = HashMap<String,ReactDiagrams.DefaultPortModel>()
	private val outPortsByData = HashMap<String,ReactDiagrams.DefaultPortModel>()

	data class Pos(
		val x: Double,
		val y: Double
	)

	val x: Double get() =
		model.getX().toDouble()
	val y: Double get() =
		model.getY().toDouble()
	var pos: Pos
		get() = model.getPosition().let {
			Pos(
				x = it.x.toDouble(),
				y = it.y.toDouble()
			)
		}
		set(value) {
			model.setPosition(value.x, value.y)
		}

	init {

		// make sure the inputs and outputs don't overlap, since it actually happened
		if (config.inputs.any { input -> config.outputs.any { output -> input.id == output.id } }) {
			throw IllegalStateException("inputs overlap with outputs for node ${config.id}:"
				+ "\n\tinputs: ${config.inputs.map { it.name }}"
				+ "\n\toutputs: ${config.inputs.map { it.name }}"
			)
		}

		// create ports for the data
		for (data in config.inputs) {
			val port = model.addInPort(data.id, data.name)
			port.setLocked(true)
			inPortsByData[data.id] = port
			// TODO: need click actions here?
		}
		for (data in config.outputs) {
			val port = model.addOutPort(data.id, data.name)
			port.setLocked(true)
			outPortsByData[data.id] = port
			diagram.onClick.register(port) {
				onOutPortClick?.invoke(data)
			}
		}

		// initialize the position
		model.setPosition(baseJob.common.x, baseJob.common.y)
	}

	val inPorts: List<ReactDiagrams.PortModel> =
		config.inputs.mapNotNull { inPortsByData[it.id] }

	val outPorts: List<ReactDiagrams.PortModel> =
		config.outputs.mapNotNull { outPortsByData[it.id] }

	fun getInPort(data: NodeConfig.Data) =
		inPortsByData[data.id]

	fun getInPortOrThrow(data: NodeConfig.Data) =
		getInPort(data) ?: throw NoSuchElementException("no in port in node ${config.id} for data: ${data.id}")

	fun getOutPort(data: NodeConfig.Data) =
		outPortsByData[data.id]

	fun getOutPortOrThrow(data: NodeConfig.Data) =
		getOutPort(data) ?: throw NoSuchElementException("no out port in node ${config.id} for data: ${data.id}")

	var onOutPortClick: ((NodeConfig.Data) -> Unit)? = null
	var onDeleteClick: (() -> Unit)? = null
	var onEdited: (() -> Unit)? = null
	var onCopyClick: (() -> Unit)? = null
	var onMakeRunnableClick: (() -> Unit)? = null
	var onDeleteFilesClick: (() -> Unit)? = null
	var onShowLatestLogClick: (() -> Unit)? = null

	var selected: Boolean
		get() = model.selected
		set(value) { model.selected = value }

	data class Upstream(
		val node: Node,
		/** data leaving this node */
		val outData: NodeConfig.Data,
		/** data entering the downstream node */
		val inData: NodeConfig.Data
	) {
		fun outDataId(): CommonJobData.DataId =
			CommonJobData.DataId(node.jobId, outData.id)

		fun inDataId(node: Node): CommonJobData.DataId =
			CommonJobData.DataId(node.jobId, inData.id)
	}

	/**
	 * Returns all nodes whose outputs are linked into this node
	 */
	fun getUpstreams(): List<Upstream> =
		inPorts.flatMap { inPort ->
			val inData = config.getInputOrThrow(inPort.getName())
			inPort.getLinks().values.mapNotNull m@{ link ->
				val outPort = link.getSourcePort()
					?: return@m null
				val node = diagram.findNode(outPort.getNode())
					?: return@m null
				val outData = node.config.getOutputOrThrow(outPort.getName())
				Upstream(node, outData, inData)
			}
		}

	/**
	 * Returns all nodes whose outputs are linked to this node
	 */
	fun getUpstreamNodes(): List<Node> =
		getUpstreams()
			.map { it.node }

	/**
	 * Returns the one node whose outputs are linked to this node.
	 * It no node is upstream, an error will be thrown.
	 * If more than one node matches the criterion, an error will be thrown.
	 */
	fun getUpstreamNodeOrThrow(): Node {
		val nodes = getUpstreams()
		return when (nodes.size) {
			0 -> throw IllegalStateException("no upstream node found, but expected one")
			1 -> nodes.first().node
			else -> throw IllegalStateException("multiple nodes found, but expected only one")
		}
	}

	/**
	 * Gets all the upstream nodes, and their upstream nodes, and their upstream nodes, and so on.
	 * Nodes are returned in BFS order.
	 */
	fun getUpstreamNodesIteratively(): List<Node> =
		iterate(Node::getUpstreamNodes)

	/**
	 * Returns all nodes whose inputs are linked from this node
	 */
	fun getDownstreamNodes(): List<Node> =
		outPorts
			.flatMap { it.getLinks().values }
			.mapNotNull { it.getTargetPort()?.getNode() }
			.mapNotNull { diagram.findNode(it) }

	/**
	 * Gets all the downstream nodes, and their downstream nodes, and their downstream nodes, and so on.
	 * Nodes are returned in BFS order.
	 */
	fun getDownstreamNodesIteratively(): List<Node> =
		iterate(Node::getDownstreamNodes)

	private fun iterate(generator: Node.() -> List<Node>): List<Node> {

		val out = ArrayList<Node>()
		val outLookup = HashSet<String>()

		var batch = generator()
			.deduplicate()
		while (batch.isNotEmpty()) {
			out.addAll(batch)
			outLookup.addAll(batch.map { it.jobId })
			batch = batch
				.flatMap { it.generator() }
				.deduplicate()
				.filter { it.jobId !in outLookup }
		}

		return out
	}


	/**
	 * Sets the buttons of the diagram node.
	 * Uses react elements.
	 */
	fun buttons(block: ReactBuilder.() -> Unit) {
		model.setButtons(*React.elems(block))
		diagram.update()
	}

	/**
	 * Sets the content of the diagram node.
	 * Uses react elements.
	 */
	fun content(clear: Boolean = false, block: ReactBuilder.() -> Unit) {
		if (clear) {
			model.setContent()
			diagram.update()
		}
		model.setContent(*React.elems(block))
		diagram.update()
	}

	enum class Status {
		Unknown,
		Changed,
		Waiting,
		Running,
		Ended
	}

	var status = Status.Unknown
		set(value) {
			field = value
			renderButtons()
		}

	enum class EditMode(val enabled: Boolean, val label: String, val title: String) {
		None(false, "Read/Edit", "Reading and editing is disabled"),
		Full(true, "Edit", "Edit the block options"),
		ReadOnly(true, "Read", "See the block options")
	}

	fun renderButtons() {

		buttons {

			// show the status indicators
			when (status) {

				Status.Unknown -> {
					i("fas fa-spinner fa-pulse")
				}

				Status.Changed -> {
					i("fas fa-asterisk", title = "This block has changes")
				}

				Status.Waiting -> {
					i("fas fa-clock", title = "This block is waiting to run")
				}

				Status.Running -> {
					i("fas fa-cog fa-spin")
				}

				Status.Ended -> {
					if (baseJob.common.stale || getUpstreamNodesIteratively().any { it.baseJob.isChanged() }) {
						i("fas fa-recycle", title = "This block is out-of-date")
					}
				}
			}

			val enableDelete = when (status) {

				Status.Changed,
				Status.Ended -> true

				else -> false
			}

			val editMode = when (status) {

				Status.Unknown -> EditMode.None

				Status.Changed,
				Status.Ended -> if (project.canWrite()) {
						EditMode.Full
					} else {
						EditMode.ReadOnly
					}

				Status.Waiting,
				Status.Running -> EditMode.ReadOnly
			}


			// add a menu button
			dropdownButton(
				className = "btn",
				title = "Menu",
				onMouseDown = { event ->
					// The parent dom node of this one listens to mousedown events
					// to handle node dragging and selection, but we don't want to
					// do any of those things when clicking this button, so prevent
					// the event from bubbling up to the parent dom node.
					event.stopPropagation()
				},
				buttonBlock = {
					icon("fas fa-bars")
				},
				menuBlock = {

					// rename link
					dropdownItem(enabled = project.canWrite(), title = "Rename this block", onClick = {
						showRename()
					}) {
						icon("fas fa-tag", className = "icon")
						text("Rename")
					}

					// copy link
					dropdownItem(enabled = project.canWrite() && onCopyClick != null, title = "Make a copy of this block", onClick = {
						onCopyClick?.invoke()
					}) {
						icon("far fa-clone", className = "icon")
						text("Copy")
					}

					// edit link
					dropdownItem(enabled = editMode.enabled, title = editMode.title, onClick = {
						when (editMode) {
							EditMode.None -> Unit
							EditMode.Full -> onEdit(enabled = true)
							EditMode.ReadOnly -> onEdit(enabled = false)
						}
					}) {
						when (editMode) {
							EditMode.None,
							EditMode.Full -> icon("fas fa-edit", className = "icon")
							EditMode.ReadOnly -> icon("far fa-file-alt", className = "icon")
						}
						text(editMode.label)
					}

					dropdownDivider()

					// show log link
					dropdownItem(enabled = onShowLatestLogClick != null, title = "Navigate to the most recent logs for this block", onClick = {
						onShowLatestLogClick?.invoke()
					}) {
						icon("fas fa-external-link-alt", className = "icon")
						text("Navigate to latest logs")
					}

					// show folder link
					dropdownItem(title = "Shows the filesystem location for this project block", onClick = {
						PathPopup.show("${baseJob.numberedName} filesystem location", baseJob.common.path)
					}) {
						icon("fas fa-location-arrow", className = "icon")
						text("Show filesystem location")
					}

					dropdownDivider()

					// wipe links
					dropdownItem(enabled = project.canWrite() && !baseJob.common.stale, title = "Make this block runnable", onClick = {
						onMakeRunnableClick?.invoke()
					}) {
						icon("fas fa-recycle", className = "icon")
						text("Make runnable")
					}

					dropdownItem(enabled = project.canWrite(), title = "Delete any intermediate files and data produced by this block, but keep the block configuration", onClick = {
						onDeleteFilesClick?.invoke()
					}) {
						icon("fas fa-eraser", className = "icon")
						text("Delete files and data")
					}

					dropdownDivider()

					// delete link
					dropdownItem(enabled = project.canWrite() && enableDelete, title = "Delete this block", onClick = {
						onDeleteClick?.invoke()
					}) {
						icon("fas fa-trash", className = "icon")
						text("Delete")
					}

					// debug options
					if (Admin.info.peek()?.debug == true) {

						dropdownDivider()
						dropdownHeader {
							text("Debug Tools:")
						}

						// tool to show the all of the pyp args saved in this block
						dropdownItem(title = "Show pyp args", onClick = {
							showPypArgs(this@Node)
						}) {
							icon("fas fa-user-shield", className = "icon")
							text("pyp args")
						}
					}
				}
			)
		}
	}

	abstract fun renderContent(refreshImages: Boolean)

	/**
	 * called when then job is queued for running so the args can be updated
	 */
	abstract fun onRunInit()

	abstract fun onEdit(enabled: Boolean = false)

	/**
	 * called after onEdit() overrides have made changes to this node/job
	 */
	protected fun edited() {

		// update the changed indicators
		if (status == Status.Ended && baseJob.isChanged()) {
			status = Status.Changed
		} else if (status == Status.Changed && !baseJob.isChanged()) {
			status = Status.Ended
		}

		// notify listeners
		onEdited?.invoke()
	}

	private fun showRename() {

		val win = Modal(
			caption = "Rename",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup")
		)

		val nameText = win.textInput(value = baseJob.name)
		val renameButton = Button("Rename")
			.also { win.addButton(it) }

		fun submit() {

			// get the name
			val name = nameText.value
				?: return

			win.hide()

			// apply the name change
			AppScope.launch {
				baseJob = JobData.deserialize(Services.projects.renameJob(jobId, name))
			}
		}

		// wire up events
		renameButton.onClick { submit() }
		nameText.onEnter { submit() }

		win.focusASAP(nameText)
		win.show()
	}

	fun render(refreshImages: Boolean = false) {
		renderButtons()
		renderContent(refreshImages)
	}

	val projectFolder: String get() =
		project.projectId

	val jobFolder: String get() =
		"${config.id}-$jobId"

	val dir: String get() =
		"$projectFolder/$jobFolder"

	fun newestArgValues(): ArgValuesToml? =
		baseJob.newestArgValues()

	/**
	 * Returns true iff the node argument values have satisfied all the requirements,
	 * like all required arguments should have values
	 */
	suspend fun validate(): Boolean {

		val values = newestArgValues()
			?: return false

		val args = config.clientInfo.pypArgs.get()
		val argValues = values.toArgValues(args)

		// just check for missing required values for now
		return args.args.none { arg ->
			arg.required && argValues[arg] == null
		}
	}

	/**
	 * Gets the raw HTML DOM element that was created by React
	 */
	fun getHTMLElement(): HTMLElement? =
		document
			.querySelector("div[data-nodeid=\"${model.getID()}\"]")
			as? HTMLElement
}


fun List<Node>.deduplicate(): List<Node> {
	val lookup = HashSet<String>()
	return filter {
		if (it.jobId in lookup) {
			false
		} else {
			lookup.add(it.jobId)
			true
		}
	}
}


private fun showPypArgs(node: Node) {
	AppScope.launch {

		val win = Modal(
			caption = "pyp args",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup")
		)

		suspend fun renderArgs(toml: ArgValuesToml?): Div {

			val elem = Div(classes = setOf("debug-pyp-args", "max-height-dialog"))

			if (toml == null) {
				elem.div("(no pyp args in this position)", classes = setOf("empty", "spaced"))
				return elem
			}

			// parse the args
			val args = node.config.clientInfo.pypArgs.get()
			val values = toml.toArgValues(args)

			// render them in the groups
			for (group in args.groups) {
				elem.h2(group.groupId)
				elem.div(classes = setOf("group")) {
					for (arg in args.args(group)) {
						div(classes = setOf("arg")) {
							span(arg.argId, classes = setOf("argId"))
							span(":")
							val value = values[arg]
							if (value == null) {
								if (arg.default != null) {
									span("(default)", classes = setOf("empty"))
								} else {
									span("(not present)", classes = setOf("empty"))
								}
							} else {
								span("(${value::class.simpleName})", classes = setOf("type"))
								span(":")
								span(value.toString(), classes = setOf("value"))
							}
						}
					}
				}
			}

			return elem
		}

		val renderedFinished = renderArgs(node.baseJob.finishedArgValues())
		val renderedNext = renderArgs(node.baseJob.nextArgValues())

		win.tabPanel {
			addTab("Finished", renderedFinished)
			addTab("Next", renderedNext)
		}

		win.show()
	}
}
