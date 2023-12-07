package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.links.DRI


class Model {

	companion object {

		fun typeId(packageName: String, classNames: String): String =
			"$packageName/$classNames"
	}

	class Service(
		val dri: DRI,
		val name: String,
		val functions: List<Function>
	) {

		class Function(
			val dri: DRI,
			val mode: Mode,
			val name: String,
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

			data class Argument(
				val name: String,
				val type: TypeRef
			)
		}
	}
	val services = ArrayList<Service>()

	data class TypeRef(
		val packageName: String,
		val name: String,
		val params: List<TypeRef>
	) {

		val id: String get() =
			typeId(packageName, name)

		var nullable: Boolean = false
	}

	data class Type(
		val packageName: String,
		val name: String,
		val props: List<Property>,
		// TODO: params?
		val inners: List<Type>
	) {

		data class Property(
			val name: String,
			val type: TypeRef
		)

		val ancestry: List<Type> get() {
			val out = ArrayList<Type>()
			var t: Type? = this
			while (t != null) {
				out.add(t)
				t = t.outer
			}
			return out
		}

		var outer: Type? = null

		val id: String get() =
			typeId(packageName, ancestry.reversed().joinToString(".") { it.name })
	}
	val types = ArrayList<Type>()

	fun typeRefs(): HashMap<String,TypeRef> {

		val out = HashMap<String,TypeRef>()

		fun add(typeRef: TypeRef) {

			if (typeRef.id in out) {
				return
			}

			out[typeRef.id] = typeRef

			// recurse
			typeRef.params.forEach { add(it) }
		}

		for (service in services) {
			for (func in service.functions) {
				func.arguments.forEach { add(it.type) }
				func.returns?.let { add(it) }
			}
		}

		return out
	}

	fun typesLookup(): Map<String,Type> {

		val out = HashMap<String,Type>()

		fun add(type: Type) {

			if (type.id in out) {
				throw IllegalStateException("duplicate type id: ${type.id}")
			}

			out[type.id] = type

			// recurse
			type.inners.forEach { add(it) }
		}

		for (type in types) {
			add(type)
		}

		return out
	}
}
