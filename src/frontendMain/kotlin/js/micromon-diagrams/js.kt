package js.micromondiagrams

import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import js.react.React
import js.reactdiagrams.ReactCanvasCore
import js.reactdiagrams.ReactDiagrams
import js.reactdiagrams.ReactDiagrams.DefaultPortModel


@JsModule("micromon-diagrams")
@JsNonModule
external object MicromonDiagrams {

	interface NodeType {
		val id: String
		val name: String
		val iconClass: String
		fun model(): NodeModel
	}

	fun nodeType(id: String, name: String, iconClass: String): NodeType

	class NodeModel : ReactDiagrams.NodeModel {
		var name: String
		val type: NodeType
		val hasSize: Boolean
		var inputsExclusive: Boolean
		var onTitleMouseDown: () -> Unit
		var onTitleMouseUp: () -> Unit
		fun onSize(listener: () -> Unit)
		fun setButtons(vararg elems: React.Element)
		fun setContent(vararg elems: React.Element)
		fun addInPort(name: String, label: String): DefaultPortModel
		fun addOutPort(name: String, label: String): DefaultPortModel
		var selected: Boolean
	}

	fun registerNodeFactory(engine: ReactDiagrams.DiagramEngine, type: NodeType)
	fun registerAction(engine: ReactDiagrams.DiagramEngine, type: ReactCanvasCore.InputType, callback: (ReactCanvasCore.BaseModel?) -> Unit)
}


/* tragically, this causes a compiler bug T_T, so we can't use it
	something about a spread function on an extension receiver

fun MicromonDiagrams.NodeModel.content(block: ReactBuilder.() -> Unit) {
	setContent(*React.elems(block))
}
*/

fun MicromonDiagrams.nodeType(config: NodeConfig, iconClass: String) =
	MicromonDiagrams.nodeType(config.id, config.name, iconClass)


fun List<MicromonDiagrams.NodeModel>.allSized(listener: () -> Unit) {

	var sized = 0

	for (node in this) {
		node.onSize {
			sized += 1
			if (sized == size) {
				listener()
			}
		}
	}
}
