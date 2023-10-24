package edu.duke.bartesaghi.micromon.diagram

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.childElements
import edu.duke.bartesaghi.micromon.diagram.nodes.Node
import edu.duke.bartesaghi.micromon.services.JobPosition
import edu.duke.bartesaghi.micromon.services.Services
import js.cola.Cola
import js.getHTMLElementOrThrow
import js.micromondiagrams.MicromonDiagrams
import js.react.ReactDOM
import js.reactdiagrams.*
import kotlinext.js.jsObject
import io.kvision.core.Container
import io.kvision.html.Div
import js.cola.ColaFixed
import js.values
import kotlinx.dom.addClass
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement


class Diagram(
	container: Container
): Div(classes = setOf("diagram")) {

	companion object {

		private val nodeTypes: MutableSet<MicromonDiagrams.NodeType> = HashSet()

		fun register(nodeType: MicromonDiagrams.NodeType) {
			nodeTypes.add(nodeType)
		}
	}

	init {
		// add to the DOM immediately
		container.add(this)
	}

	val elem = getHTMLElementOrThrow()

	private val model = ReactDiagrams.DiagramModel()
	private var titleMousedNode: Node? = null
	private var oldNodePositions: Map<String,Node.Pos>? = null

	data class MovedNode(
		val node: Node,
		val oldPos: Node.Pos,
		val newPos: Node.Pos,
	)

	private val engine = ReactDiagrams.default(jsObject {

		// don't register the default zoom action, since it uses the opposite directons
		// one would expect from eg map apps. We'll have to register our own zoom action
		// to override the defaults
		registerDefaultZoomCanvasAction = false

		// don't let the backspace/delete keys delete nodes by default
		registerDefaultDeleteItemsAction = false

	}).apply {

		setModel(model)

		// register our "inverted" zoom action here
		eventBus.registerAction(ReactCanvasCore.ZoomCanvasAction(jsObject {
			inverseZoom = true
		}))

		val canvas = ReactCanvasCore.CanvasWidget.create(jsObject {
			this.engine = this@apply
		})
		ReactDOM.render(canvas, elem)

		// try to capture drag-release events
		var oldStateName: String? = null
		getStateMachine().registerListener(jsObject {
			eventDidFire = handler@{ event ->
				if (event.function == "stateChanged") {

					// react-diagram's types are a bit of a mess,
					// so the best we can do is hope this cast is correct
					event as ReactCanvasCore.StateChangedEvent

					val stateName = event.newState.options.name

					// look for a transition into the move-items state
					if (stateName == "move-items") {

						// record all the node positions
						oldNodePositions = nodes().associate { it.jobId to it.pos }
					}

					// look for a transition out of the move-items state to signal the end of a drag-n-drop
					if (oldStateName == "move-items" && stateName != "move-items") {
						oldNodePositions?.let { handleDragEnd(it) }
						oldNodePositions = null
					}

					oldStateName = stateName
				}
			}
		})
	}

	val stageContainerElem: HTMLElement =
		elem
			.childElements.find { it is HTMLDivElement }
			?: throw NoSuchElementException("no stage container for diagram")

	val stageElem: HTMLElement =
		// it's the first div inside the first div
		// sadly, there are no other identifying attributes... =( blame ReactJS
		stageContainerElem
			.childElements.find { it is HTMLDivElement }
			?: throw NoSuchElementException("no stage for diagram")

	init {
		// apply styles to the stage
		stageContainerElem.addClass("stage-container")
		stageElem.addClass("stage")
	}

	private val registeredNodeTypes = HashSet<MicromonDiagrams.NodeType>()

	/** indexed by node model id */
	private val nodes = HashMap<String,Node>()

	fun nodes(): Iterable<Node> = nodes.values

	fun findNode(jobId: String): Node? =
		nodes.values.find { it.jobId == jobId }
		// probably there aren't many nodes at once, so linear search here is fine for now

	fun findNode(nodeModel: ReactDiagrams.NodeModel): Node? =
		nodes.values.find { it.model === nodeModel }

	fun addNode(node: Node) {

		// register factories if needed
		if (node.model.type !in registeredNodeTypes) {
			MicromonDiagrams.registerNodeFactory(engine, node.model.type)
			registeredNodeTypes.add(node.model.type)
		}

		model.addNode(node.model)

		nodes[node.model.getID()] = node

		// add title mouse listeners
		node.model.onTitleMouseDown = {
			titleMousedNode = node
		}

		node.model.registerListener(jsObject {
			eventDidFire = { event ->
				when (event.function) {
					"positionChanged" -> {

						// is this the dragging node?
						if (node == titleMousedNode) {
							oldNodePositions?.let { handleDrag(it, node) }
						}
					}
				}
			}
		})
	}

	private fun handleDrag(oldNodePositions: Map<String,Node.Pos>, node: Node) {

		val otherSelectedNodes = nodes.values
			.filter { it.selected && it !== node }
			.takeIf { it.isNotEmpty() }
			?: return

		// calculate the delta for the dragging node
		val oldPos = oldNodePositions[node.jobId]
			?: return
		val dx = node.x - oldPos.x
		val dy = node.y - oldPos.y

		// apply the same delta to the other nodes
		for (otherNode in otherSelectedNodes) {
			val otherOldPos = oldNodePositions[otherNode.jobId]
				?: continue
			otherNode.pos = Node.Pos(
				x = otherOldPos.x + dx,
				y = otherOldPos.y + dy
			)
		}
	}

	private fun handleDragEnd(oldNodePositions: Map<String,Node.Pos>) {

		// which nodes, if any, changed?
		val changed = ArrayList<MovedNode>()
		for (node in nodes()) {
			val oldPos = oldNodePositions[node.jobId]
				?: continue
			val newPos = node.pos
			if (newPos != oldPos) {
				changed.add(MovedNode(node, oldPos, newPos))
			}
		}

		// if any nodes moved, send a drag up event
		if (changed.isNotEmpty()) {
			onDragged(changed)
			return
		}

		// otherwise, look for node title mouse events
		val titleMousedNode = titleMousedNode
		if (titleMousedNode != null) {
			this.titleMousedNode = null

			// toggle the selection
			titleMousedNode.selected = !titleMousedNode.selected
			update()

			val selectedNodes = nodes.values
				.filter { it.selected }
				.toList()
			onSelectionChanged(selectedNodes)
		}
	}

	fun removeNode(node: Node) {
		model.removeNode(node.model)
		nodes.remove(node.model.getID())
	}

	fun addLink(link: ReactDiagrams.LinkModel) {

		// HACKHACK: force the link model to use the correct port points
		// react diagrams is supposed to do this automatically, but for some reason, it's not. maybe bug?
		listOf(link.getSourcePort(), link.getTargetPort())
			.mapNotNull { it }
			.forEach { port ->
				link.getPointForPort(port).setPosition(port.getCenter())

				// "lock" the links so we can't edit them with the mouse
				link.setLocked(true)
			}

		model.addLink(link)
	}

	fun removeLink(link: ReactDiagrams.LinkModel) {
		model.removeLink(link)
	}

	fun place(node: Node) = node.model.onSize {
		// NOTE: we're setting an onSize event handler here,
		//       but the handler gets called only once at the end of the event queue.
		//       We have to wait for the event to get the node size info though.

		// start the new node at an arbitrary point
		var newPos = Node.Pos(50.0, 50.0)

		// if we have other nodes, use the layout engine to find a good spot
		if (nodes.size > 1) {

			// keep the nodes from being too close to each other
			val margin = 10.0

			// convert the existing nodes to Cola format
			val colaNodes = ArrayList<Cola.Node>()
			val colaNodesLookup = HashMap<String,Cola.Node>()
			for (n in nodes.values) {
				val colaNode = jsObject<Cola.Node> {
					x = n.x - margin
					y = n.y - margin
					fixed = ColaFixed.FIXED
					// NOTE: FIXED sort of works, but it's not strictly enforced.
					//   The layout engine will still move "fixed" boxes to avoid overlaps.
					//   Can't find a way to disable that! D=
					width = n.model.width.toDouble() + margin*2
					height = n.model.height.toDouble() + margin*2
					index = colaNodes.size
				}
				colaNodes.add(colaNode)
				colaNodesLookup[n.model.getID()] = colaNode
			}

			// gather the upstream nodes
			val upstreamColaNodes = ArrayList<Cola.Node>()
			for (port in node.inPorts) {
				for (link in port.getLinks().values) {
					val nodeModel = link.getSourcePort()?.getNode() ?: continue
					val upstreamColaNode = colaNodesLookup[nodeModel.getID()] ?: continue
					upstreamColaNodes.add(upstreamColaNode)
				}
			}

			// allow the placing node to move
			val placingColaNode = colaNodesLookup.getValue(node.model.getID())
			placingColaNode.fixed = ColaFixed.MOBILE

			// and start it to the right of the first upstream node, if any
			upstreamColaNodes.firstOrNull()
				?.let { upstreamColaNode ->
					placingColaNode.x = (upstreamColaNode.x?.toDouble() ?: 0.0) + (upstreamColaNode.width?.toDouble() ?: 0.0) + margin
					placingColaNode.y = (upstreamColaNode.y?.toDouble() ?: 0.0)
				}

			/* DEBUG
			colaNodes.forEach {
				console.log("initial", it.index, it.x?.toInt(), it.y?.toInt())
			}
			val initials = colaNodes.map { (it.x?.toInt() ?: 0) to (it.y?.toInt() ?: 0) }
			*/

			// translate the links too
			val links = upstreamColaNodes.map { upstreamColaNode ->
				jsObject<Cola.Link> {
					source = upstreamColaNode
					target = placingColaNode
				}
			}

			// constrain the placing node to be to the right of the upstream node(s)
			val constraints = ArrayList<dynamic>()
			for (upstreamColaNode in upstreamColaNodes) {
				constraints.add(jsObject {
					// ie: left + gap <= right
					this.axis = "x"
					this.left = upstreamColaNode.index
					this.right = placingColaNode.index
					this.gap = (upstreamColaNode.width?.toDouble() ?: 0.0) + 40.0
				})
			}

			// run the layout engine
			Cola.adaptor(jsObject {})
				.nodes(colaNodes.toTypedArray())
				.links(links.toTypedArray())
				.constraints(constraints.toTypedArray())
				.avoidOverlaps(true)
				.handleDisconnected(false) // tries to move disconnected components together, not needed here
				.start(
					initialUnconstrainedIterations = 0,
					initialUserConstraintIterations = 0,
					initialAllConstraintsIterations = 50,
					gridSnapIterations = 0,
					centerGraph = false,
					keepRunning = false
				)

			/* DEBUG
			val finals = colaNodes.map { (it.x?.toInt() ?: 0) to (it.y?.toInt() ?: 0) }
			for ((i, entry) in initials.zip(finals).withIndex()) {
				val (init, fin) = entry
				if (init != fin) {
					val n = colaNodes[i]
					console.log("moved", n.index, fin.first, fin.second)
				}
			}
			*/

			// save the new position
			newPos = Node.Pos(
				x = (placingColaNode.x?.toDouble() ?: 0.0) + margin,
				y = (placingColaNode.y?.toDouble() ?: 0.0) + margin
			)
		}

		// finally, update the placing node position
		node.pos = newPos
		update()

		// save the position on the server too
		AppScope.launch {
			Services.projects.positionJobs(listOf(
				JobPosition(node.jobId, node.x, node.y)
			))
		}
	}

	fun zoomOutToFitNodes() {

		// sadly, the built-in version doesn't do what we want
		//engine.zoomToFitNodes(10)

		// so compute an axis-aligned bounding box over all the nodes
		var aabb = Geometry.Polygon.boundingBoxFromPolygons(nodes()
			.map { it.model.getBoundingBox() }
			.toTypedArray()
		)

		// pad the box a bit
		val pad = 50.0
		aabb = Geometry.Rectangle(
			aabb.getTopLeft().x.toDouble() - pad,
			aabb.getTopLeft().y.toDouble() - pad,
			aabb.getWidth().toDouble() + pad*2,
			aabb.getHeight().toDouble() + pad*2
		)

		val canvas = engine.getCanvas()

		// calculate the zoom that fits everything on the canvas
		var zoom = listOf(
			canvas.clientWidth / aabb.getWidth().toDouble(),
			canvas.clientHeight / aabb.getHeight().toDouble()
		).minOrNull()!!

		// don't zoom in, only zoom out
		if (zoom > 1.0) {
			zoom = 1.0
		}

		model.setZoomLevel(zoom*100)

		// calculate the offset
		model.setOffset(
			-aabb.getTopLeft().x.toDouble()*zoom,
			-aabb.getTopLeft().y.toDouble()*zoom,
		)

		update()
	}

	fun update() {
		engine.repaintCanvas()
	}

	var locked
		get() = model.isLocked()
		set(value) { model.setLocked(value) }

	/** An action registration system for diagram models */
	inner class Actions {

		inner class Registration(
			val unregister: () -> Unit
		)

		private inner class Handlers(val id: String) : Iterable<() -> Unit> {

			private val fns = ArrayList<() -> Unit>()

			fun register(fn: () -> Unit): Registration {

				// register the handler
				fns.add(fn)

				return Registration {

					// unregister the handler
					fns.remove(fn)

					// clear the handlers instance if this was the last fn
					if (fns.isEmpty()) {
						handlers.remove(id)
					}
				}
			}

			val size get() = fns.size

			override fun iterator() = fns.iterator()
		}

		private val handlers = HashMap<String,Handlers>()

		fun register(model: ReactCanvasCore.BaseModel, handler: () -> Unit) {
			val id = model.getID()
			handlers.getOrPut(id) { Handlers(id) }.register(handler)
		}

		fun handle(model: ReactCanvasCore.BaseModel?) {
			val id = model?.getID() ?: return
			handlers[id]?.forEach { it() }
		}
	}

	val onClick = Actions().apply {
		MicromonDiagrams.registerAction(engine, ReactCanvasCore.InputType.MOUSE_DOWN) { handle(it) }
	}

	var onDragged: (List<MovedNode>) -> Unit = {}

	/** only fires on changes initiated by the user, not by directly setting node.selected */
	var onSelectionChanged: (selectedNodes: List<Node>) -> Unit = {}
}
