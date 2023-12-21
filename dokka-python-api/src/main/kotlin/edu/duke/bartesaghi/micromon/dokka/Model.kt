package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.links.DRI


private const val PP = "edu.duke.bartesaghi.micromon"
const val PACKAGE_SERVICES = "$PP.services"
const val PACKAGE_PYP = "$PP.pyp"

val PACKAGES = setOf(PACKAGE_SERVICES, PACKAGE_PYP)


class Model {

	companion object {

		fun typeId(packageName: String, classNames: String): String =
			"$packageName/$classNames"

		fun typeId(dri: DRI): String =
			typeId(dri.packageName ?: "", dri.classNames ?: "")
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

	class RealtimeService(
		val dri: DRI,
		val name: String,
		val path: String,
		val messagesC2S: List<TypeRef>,
		val messagesS2C: List<TypeRef>
	)
	val realtimeServices = ArrayList<RealtimeService>()

	data class TypeRef(
		val packageName: String,
		val name: String,
		val params: List<TypeRef>
	) {

		val id: String get() =
			typeId(packageName, name)

		var nullable: Boolean = false

		val innerName: String get() =
			name.split('.').last()
	}

	data class Type(
		val packageName: String,
		val name: String,
		val props: List<Property>,
		val enumValues: List<String>?,
		val inners: MutableList<Type>
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

		val names: String get() =
			ancestry.reversed().joinToString(".") { it.name }

		val id: String get() =
			typeId(packageName, names)

		fun descendents(out: ArrayList<Type> = ArrayList()): ArrayList<Type> {
			for (inner in inners) {
				out.add(inner)
				inner.descendents(out)
			}
			return out
		}
	}
	val types = ArrayList<Type>()

	fun typeRefs(): HashMap<String,TypeRef> {

		val out = HashMap<String,TypeRef>()

		fun add(typeRef: TypeRef) {

			if (typeRef.id !in out) {
				out[typeRef.id] = typeRef
			}

			// recurse
			typeRef.params.forEach { add(it) }
		}

		// get all the type refs in service functions
		for (service in services) {
			for (func in service.functions) {
				func.arguments.forEach { add(it.type) }
				func.returns?.let { add(it) }
			}
		}

		// get all the type refs in realtime services
		for (realtimeService in realtimeServices) {
			realtimeService.messagesC2S.forEach { add(it) }
			realtimeService.messagesS2C.forEach { add(it) }
		}

		fun addAllIn(type: Type) {

			// add type properties
			for (prop in type.props) {
				add(prop.type)
			}

			// recurse
			for (inner in type.inners) {
				addAllIn(inner)
			}
		}

		// and the properties of types too
		for (type in types) {
			addAllIn(type)
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
