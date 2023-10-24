package js.react

import org.w3c.dom.Element
import org.w3c.dom.HTMLElement


@JsModule("react")
@JsNonModule
external object React {

	// https://github.com/JetBrains/kotlin-wrappers/blob/master/kotlin-react/src/main/kotlin/react/ReactElement.kt
	interface Element

	// https://github.com/JetBrains/kotlin-wrappers/blob/master/kotlin-react/src/main/kotlin/react/Component.kt
	abstract class Component(props: Any? = definedExternally) {
		abstract fun render(): dynamic
	}

	fun createElement(
		type: dynamic /* { String, constructor, class } */,
		props: dynamic = definedExternally,
		vararg children: dynamic /* { String, Element } */
	): Element
}

@JsModule("react-dom")
@JsNonModule
external object ReactDOM {

	fun render(reactElem: React.Element, domElem: HTMLElement)

	// https://github.com/JetBrains/kotlin-wrappers/blob/master/kotlin-react-dom/src/main/kotlin/react/dom/findDOMNode.kt
	fun findDOMNode(component: React.Component): Element?
}
