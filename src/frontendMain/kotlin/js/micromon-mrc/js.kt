package js.micromonmrc

import org.w3c.dom.HTMLElement

@JsModule("micromon-mrc")
@JsNonModule
external object MicromonMRC {

	class Renderer {
		fun checkFirstMeshIsShowing(): Boolean
		fun checkSecondMeshIsShowing(): Boolean
		fun checkUsingDoubleViews(): Boolean
		fun init(e: HTMLElement, f: HTMLElement, size: Int)
		fun createMesh1fromData(d: Any?)
		fun createMesh2fromData(d: Any?)
		fun clearMesh1()
		fun clearMesh2()
		fun setUseDoubleViews(b: Boolean)
		fun updateRendererSize(size :Int)
		fun dispose()
	}

	fun getUserAgent(): String
}
