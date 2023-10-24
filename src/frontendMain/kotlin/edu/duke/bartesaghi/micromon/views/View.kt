package edu.duke.bartesaghi.micromon.views

import io.kvision.core.Container


interface View {

	val elem: Container
	val showsUserChrome: Boolean get() = true
	val mode: Mode? get() = Mode.Dock

	fun init(viewport: Viewport)
	fun close() {}

	enum class Mode(val className: String) {
		Dock("mode-dock")
	}
}
