package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.links.DRI


class Model {

	class Service(
		val dri: DRI,
		val name: String,
		val functions: List<Function>
	) {

		class Function(
			val dri: DRI,
			val mode: Mode,
			val path: String,
			val arguments: List<Argument>,
			val returns: TypeRef?
		) {

			sealed interface Mode {

				class KVision : Mode

				class KTor(
					// TODO: HTTP method
				): Mode
			}

			class Argument(
				val name: String,
				val type: TypeRef
			)
		}
	}
	val services = ArrayList<Service>()

	class TypeRef(
		val packageName: String,
		val name: String,
		val params: List<TypeRef>
		// TODO
	)
}
