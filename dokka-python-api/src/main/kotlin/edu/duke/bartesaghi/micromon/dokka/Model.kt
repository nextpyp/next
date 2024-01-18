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
		val doc: Doc?,
		val functions: List<Function>
	) {

		class Function(
			val dri: DRI,
			val mode: Mode,
			val name: String,
			val path: String,
			val arguments: List<Argument>,
			val returns: TypeRef?,
			val doc: Doc?,
			val appPermissionId: String?
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
		val messagesS2C: List<TypeRef>,
		val doc: Doc?,
		val appPermissionId: String?
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

		val flatName: String get() =
			name.split('.').joinToString("")
	}

	data class Type(
		val packageName: String,
		val name: String,
		val props: List<Property>,
		val enumValues: List<String>?,
		val doc: Doc?,
		val inners: MutableList<Type>
	) {

		data class Property(
			val name: String,
			val type: TypeRef,
			val doc: Doc?
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

		/**
		 * Python itself technically supports nested classes, but apparently no one actually uses them,
		 * so they're not supported by tooling like Sphinx.
		 * So just flatten them into top-level classes.
		 */
		val flatName: String get() =
			ancestry.reversed().joinToString("") { it.name }

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

	data class Doc(
		val text: String
	)

	data class Permission(
		val dri: DRI,
		val appPermissionId: String
	) {

		val isOpen: Boolean get() =
			dri.classNames == "AppPermission.Open"
	}

	private val permissions = HashMap<String,Permission>()

	fun addPermission(perm: Permission) {
		permissions[typeId(perm.dri)] = perm
	}

	fun getPermission(dri: DRI): Permission? =
		permissions[typeId(dri)]
}


/**
 * Sphinx shows very different results (and they look bad) when you don't have any text in the docstring,
 * so make sure we always show something, even if it's totally meaningless text.
 */
val Model.Doc?.textOrUndocumented: String get() =
	this?.text ?: "(no description yet)"
