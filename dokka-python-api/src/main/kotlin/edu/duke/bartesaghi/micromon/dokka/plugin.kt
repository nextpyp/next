package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.CoreExtensions
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.plugability.*


// https://kotlin.github.io/dokka/1.6.0/developer_guide/introduction/

class MicromonDokkaPlugin : DokkaPlugin() {

	// helpful to make sure the plugin is even loaded and running
	init {
		println("Activated Micromon Dokka plugin")
	}

	private val dokkaBasePlugin by lazy { plugin<DokkaBase>() }

	val pager by extending {
		CoreExtensions.documentableToPageTranslator providing { ctx -> ModelCollector(ctx) } override dokkaBasePlugin.documentableToPageTranslator
	}

	val renderer by extending {
		CoreExtensions.renderer providing { ctx -> PythonAPIRenderer(ctx) } override dokkaBasePlugin.htmlRenderer
	}
}
