package js.cola

@JsModule("webcola")
@JsNonModule
external object Cola {

	// https://github.com/tgdwyer/WebCola/blob/34433da152b590ba212fc373af608b68110aa6d1/WebCola/src/adaptor.ts#L24
	interface LayoutAdaptorOptions {
		var trigger: Any
		var kick: Any
		var drag: Any
		var on: Any
	}

	fun adaptor(options: Any): LayoutAdaptor

	// https://ialab.it.monash.edu/webcola/doc/classes/_adaptor_.layoutadaptor.html
	interface LayoutAdaptor : Layout

	// https://ialab.it.monash.edu/webcola/doc/classes/_layout_.layout.html
	interface Layout {
		fun nodes(): Array<Node>
		fun nodes(nodes: Array<out InputNode>): Layout
		fun links(): Array<Link>
		fun links(links: Array<out Link>): Layout
		fun constraints(): Array<Any>
		fun constraints(constraints: Array<Any>): Layout
		fun avoidOverlaps(): Boolean
		fun avoidOverlaps(value: Boolean): Layout
		fun convergenceThreshold(): Number
		fun convergenceThreshold(value: Number): Layout
		fun handleDisconnected(): Boolean
		fun handleDisconnected(value: Boolean): Layout
		fun start(
			initialUnconstrainedIterations: Number? = definedExternally,
			initialUserConstraintIterations: Number? = definedExternally,
			initialAllConstraintsIterations: Number? = definedExternally,
			gridSnapIterations: Number? = definedExternally,
			keepRunning: Boolean? = definedExternally,
			centerGraph: Boolean? = definedExternally
		): Layout
		fun stop(): Layout

		/** doesn't seem to actually work? =( */
		fun on(e: EventType, listener: (Event) -> Unit): Layout
	}

	interface Node : InputNode, NodeRefType

	interface InputNode {
		var index: Number?
		var x: Number?
		var y: Number?
		var width: Number?
		var height: Number?
		/**
		 * The fixed property has three bits:
		 * Bit 1 can be set externally (e.g., d.fixed = true) and show persist.
		 * Bit 2 stores the dragging state, from mousedown to mouseup.
		 * Bit 3 stores the hover state, from mouseover to mouseout.
		 */
		var fixed: Int?
		var fixedWeight: Number?
	}

	interface NodeRefType // ??? this type apparently isn't in Cola, maybe in "powergraph"?

	interface Link {
		var source: NodeRefType
		var target: NodeRefType
		var length: Number?
		var weight: Number?
	}

	enum class EventType {
		start,
		tick,
		end
	}

	interface Event {
		var type: EventType
		var alpha: Number
		var stress: Number?
		var listener: () -> Unit
	}
}

object ColaFixed {
	const val MOBILE = 0
	const val FIXED = 1
}
