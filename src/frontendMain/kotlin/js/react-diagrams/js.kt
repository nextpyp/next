package js.reactdiagrams

import js.MapObject
import js.react.React
import org.w3c.dom.HTMLDivElement


/**
 * See:
 * https://github.com/projectstorm/react-diagrams
 * https://projectstorm.gitbook.io/react-diagrams/getting-started/using-the-library
 *
 * Currently we're using v6.2.0 whose full source code is here:
 * https://github.com/projectstorm/react-diagrams/tree/v6.2.0
 *
 * Tragically, the Kotlin/JS compiler has no support for extending js classes.  ;_;
 * So even though many of these classes are abstract, they cannot be extended in Kotlin code.
 */
@JsModule("@projectstorm/react-diagrams")
@JsNonModule
external object ReactDiagrams {

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-diagrams-core/src/DiagramEngine.ts#L21
	class DiagramEngine : ReactCanvasCore.CanvasEngine {
		fun setModel(model: DiagramModel)
		fun repaintCanvas()
		fun zoomToFitNodes(margin: Number?)
	}

	class DiagramModel : ReactCanvasCore.CanvasModel {

		fun addNode(node: NodeModel): NodeModel
		fun removeNode(node: NodeModel)
		fun getNodes(): Array<NodeModel>

		fun addLink(link: LinkModel)
		fun removeLink(link: LinkModel)
		fun getLinks(): Array<LinkModel>

		fun addAll(vararg models: ReactCanvasCore.BaseModel)
	}

	class DefaultDiagramState : ReactCanvasCore.State

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/CanvasEngine.ts#L26
	interface CanvasEngineOptions {
		var registerDefaultDeleteItemsAction: Boolean
		var registerDefaultZoomCanvasAction: Boolean
	}

	fun default(options: CanvasEngineOptions): DiagramEngine

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-diagrams-core/src/entities/node/NodeModel.ts#L24
	abstract class NodeModel : ReactCanvasCore.BasePositionModel {
		var width: Number
		var height: Number
		fun getPort(name: String): PortModel?
		fun getPorts(): Array<PortModel>
		fun addPort(port: PortModel): PortModel
		fun removePort(port: PortModel)
	}

	abstract class PortModel(props: Props) : ReactCanvasCore.BasePositionModel {

		val width: Number
		val height: Number
		fun getCenter(): Geometry.Point

		fun getNode(): NodeModel
		fun getName(): String
		fun getLinks(): MapObject<LinkModel>
		fun addLink(link: LinkModel)
		fun removeLink(link: LinkModel)

		interface Props {
			@JsName("in")
			var isIn: Boolean
			var name: String
		}
	}

	abstract class LinkModel : ReactCanvasCore.BaseModel {

		fun clearPort(port: PortModel)
		fun getSourcePort(): PortModel?
		fun setSourcePort(port: PortModel)
		fun getTargetPort(): PortModel?
		fun setTargetPort(port: PortModel)
		fun addLabel(label: LabelModel)
		fun getLabels(): List<LabelModel>

		fun getPointForPort(port: PortModel): PointModel
		fun getFirstPoint(): PointModel
		fun getLastPoint(): PointModel
	}

	abstract class PointModel : ReactCanvasCore.BasePositionModel {
		fun isConnectedToPort(): Boolean
		fun getLink(): LinkModel
	}

	abstract class LabelModel(props: Props) : ReactCanvasCore.BaseModel {

		interface Props {
			var label: String
		}
	}

	class DefaultPortModel : PortModel {
		//link<T extends LinkModel>(port: PortModel, factory?: AbstractModelFactory<T>): T {
		fun link(port: PortModel): LinkModel
	}

	// see: https://github.com/projectstorm/react-diagrams/blob/master/packages/react-diagrams-routing/src/dagre/DagreEngine.ts
	class DagreEngine(options: DagreEngineOptions) {
		fun redistribute(model: DiagramModel)
	}

	interface DagreEngineOptions {
		var graph: Dagre.GraphLabel?
		var includeLinks: Boolean?
	}
}


@JsModule("@projectstorm/react-canvas-core")
@JsNonModule
external object ReactCanvasCore {

	/* NOTE: we're ignoring all templated types here
		React-Diargams apparently uses a deep inheritance hierarchy (ugh)
		and makes full use of TypeScript's rich type and templating system.
		Since it's not obvious how to translate all of that into Kotlin's type system,
		We're just going to leave off the templated types and you'll just have to make a
		few runtime casts here and there.
		It's a small price to pay for not having to deal with React-Diagrams' ridiculousuly complicated types.
	 */


	// CANVAS

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/entities/canvas/CanvasWidget.tsx#L20
	abstract class CanvasWidget : React.Component {

		interface Props {
			var engine: ReactDiagrams.DiagramEngine
		}

		// empty, just for extensions
		companion object
	}

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/entities/canvas/CanvasModel.ts
	open class CanvasModel : BaseEntity {

		fun getZoomLevel(): Number
		fun setZoomLevel(zoom: Number)

		fun getOffsetX(): Number
		fun getOffsetY(): Number
		fun setOffsetX(offsetX: Number)
		fun setOffsetY(offsetY: Number)
		fun setOffset(offsetX: Number, offsetY: Number)
	}

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/CanvasEngine.ts
	open class CanvasEngine : BaseObserver {
		val eventBus: ActionEventBus
		fun getCanvas(): HTMLDivElement
		fun getStateMachine(): StateMachine
	}


	// MODELS

	open class BasePositionModel : BaseModel {
		fun getPosition(): Geometry.Point
		fun setPosition(x: Number, y: Number)
		fun setPosition(point: Geometry.Point)
		fun getX(): Number
		fun getY(): Number
		fun getBoundingBox(): Geometry.Rectangle
	}

	interface BasePositionModelListener : BaseModelListener {
		var positionChanged: ((BaseEntityEvent) -> Unit)?
	}

	open class BaseModel : BaseEntity {
		fun getParentCanvasModel(): CanvasModel
		fun getType(): String
		fun isSelected(): Boolean
		fun setSelected(selected: Boolean = definedExternally)
		fun remove()
	}

	interface BaseModelListener : BaseEntityListener {
		var selectionChanged: ((BaseEntityEvent) -> Unit)?
		var entityRemoved: ((BaseEntityEvent) -> Unit)?
	}

	interface BaseModelOptions {
		var type: String
		var name: String
		var color: String
	}


	// ENTITIES

	open class BaseEntity : BaseObserver {
		fun getID(): String
		fun setLocked(isLocked: Boolean)
		fun isLocked(): Boolean
	}

	interface BaseEntityEvent : BaseEvent {
		val entity: BaseEntity
	}

	interface BaseEntityListener : BaseListener {
		var lockedChanged: ((BaseEntityEvent) -> Unit)?
	}

	open class BaseObserver {
		fun registerListener(listener: BaseListener): ListenerHandle
	}

	interface BaseListener {
		var eventWillFire: ((BaseEvent) -> Unit)?
		var eventDidFire: ((BaseEvent) -> Unit)?
	}

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/core/BaseObserver.ts#L3
	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/core/BaseObserver.ts#L8
	interface BaseEvent {
		val firing: Boolean
		fun stopPropagation(): Any?
		val function: String
	}

	interface ListenerHandle {
		val id: String
		fun deregister()
		val listener: BaseListener
	}


	// ACTIONS

	// https://github.com/projectstorm/react-diagrams/blob/be422431d2dc44706ea95b8fd41bbbb18e39e370/packages/react-canvas-core/src/core-actions/ActionEventBus.ts
	open class ActionEventBus {
		fun registerAction(action: Action)
	}

	// https://github.com/projectstorm/react-diagrams/blob/ce38f77027ac37ca9193c6faccc6049036906e42/packages/react-canvas-core/src/core-actions/Action.ts
	open class Action

	enum class InputType {
		MOUSE_DOWN,
		MOUSE_UP,
		MOUSE_MOVE,
		MOUSE_WHEEL,
		KEY_DOWN,
		KEY_UP
	}

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/actions/ZoomCanvasAction.ts#L4
	interface ZoomCanvasActionOptions {
		var inverseZoom: Boolean?
	}

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/actions/ZoomCanvasAction.ts#L8
	open class ZoomCanvasAction(options: ZoomCanvasActionOptions = definedExternally) : Action


	// STATES

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/core-state/State.ts#L10
	abstract class State {
		val actions: Array<Action>
		val keys: Array<String>
		val options: StateOptions
		val childStates: Array<State>
	}

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/core-state/State.ts#L6
	interface StateOptions {
		val name: String
	}

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/core-state/StateMachine.ts#L10
	class StateMachine : BaseObserver {
		fun getCurrentState(): State
	}

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/react-canvas-core/src/core-state/StateMachine.ts#L46
	interface StateChangedEvent : BaseEvent {
		val newState: State
	}
}


@JsModule("@projectstorm/geometry")
@JsNonModule
external object Geometry {

	open class Rectangle(left: Number, top: Number, width: Number, height: Number) : Polygon {

		fun getWidth(): Number
		fun getHeight(): Number

		fun getTopMiddle(): Point
		fun getBottomMiddle(): Point
		fun getLeftMiddle(): Point
		fun getRightMiddle(): Point
		fun getTopLeft(): Point
		fun getTopRight(): Point
		fun getBottomRight(): Point
		fun getBottomLeft(): Point
	}

	open class Point {
		var x: Number
		var y: Number
	}

	// https://github.com/projectstorm/react-diagrams/blob/v6.2.0/packages/geometry/src/Polygon.ts
	open class Polygon {

		companion object {
			fun boundingBoxFromPolygons(polygons: Array<Polygon>): Rectangle
		}
	}
}


@JsModule("@dagre")
@JsNonModule
external object Dagre {

	// see: https://github.com/dagrejs/dagre/wiki
	interface GraphLabel {
		var rankdir: RankDir
		var align: Align
		var nodesep: Number
		var edgesep: Number
		var ranksep: Number
		var marginx: Number
		var marginy: Number
		var acyclicer: Acyclicer?
		var ranker: String
	}

	enum class RankDir {
		TB,
		BT,
		LR,
		RL
	}

	enum class Align {
		UL,
		UR,
		DL,
		DR
	}

	enum class Acyclicer {
		greedy
	}
}

enum class DagreRanker(val id: String) {
	NetworkSimple("network-simplex"),
	TightTree("tight-tree"),
	LongestPath("longest-path")
}
