package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.Expression


private const val PACKAGE_ROOT = "edu.duke.bartesaghi.micromon"
const val PACKAGE_SERVICES = "$PACKAGE_ROOT.services"
const val PACKAGE_PYP = "$PACKAGE_ROOT.pyp"

val PACKAGES = setOf(PACKAGE_ROOT, PACKAGE_SERVICES, PACKAGE_PYP)


class Model(
	var apiVersion: String
) {

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
		val params: List<TypeRef> = emptyList(),
		val nullable: Boolean = false,
		val aliased: TypeRef? = null,
		val parameter: Boolean = false
	) {

		val id: String get() =
			typeId(packageName, name)

		val innerName: String get() =
			name.split('.').last()

		val flatName: String get() =
			name.split('.').joinToString("")

		fun resolveAliases(): TypeRef {
			var ref = this
			while (true) {
				val aliased = ref.aliased
					?: break
				ref = aliased
			}
			return ref
		}
	}

	data class Type(
		val packageName: String,
		val name: String,
		val props: List<Property>,
		val typeParams: List<Param> = emptyList(),
		val enumValues: List<String>? = null,
		val polymorphicSerialization: Boolean = false,
		val polymorphicSubtypes: MutableList<Type> = ArrayList(),
		var polymorphicSupertype: Type? = null,
		val doc: Doc? = null,
		val inners: MutableList<Type> = ArrayList()
	) {

		data class Param(val name: String) {
			val instances = HashSet<TypeRef>()
		}

		data class Property(
			val name: String,
			val type: TypeRef,
			val default: Expression? = null,
			val doc: Doc? = null
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

		val parameterizedName: String get() =
			typeParams
				.takeIf { it.isNotEmpty() }
				?.let { params -> "$flatName[${params.joinToString(",") { it.name }}]" }
				?: flatName

		fun descendents(out: ArrayList<Type> = ArrayList()): ArrayList<Type> {
			for (inner in inners) {
				out.add(inner)
				inner.descendents(out)
			}
			return out
		}
	}
	val types = ArrayList<Type>()
	val externalTypes = ArrayList<Type>()

	fun typeRefs(): List<TypeRef> {

		val out = ArrayList<TypeRef>()

		fun add(typeRef: TypeRef) {

			out.add(typeRef)

			// recurse
			typeRef.params.forEach { add(it) }
			typeRef.aliased?.let { add(it) }
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

	fun findType(ref: TypeRef): Type? =
		types
			.find { it.packageName == ref.packageName && it.name == ref.name }


	fun findExternalType(ref: TypeRef): Type? =
		externalTypes
			.find { it.packageName == ref.packageName && it.name == ref.name }

	data class TypeAlias(
		val packageName: String,
		val name: String,
		val typeParams: List<Type.Param> = emptyList(),
		val ref: TypeRef
	) {

		val id: String get() =
			typeId(packageName, name)

		val isOption: Boolean get() =
			packageName == PACKAGE_SERVICES && name == "Option"

		val isToString: Boolean get() =
			ref.packageName == "kotlin" && ref.name == "String"
	}
	val typeAliases = ArrayList<TypeAlias>()

	fun findTypeAlias(ref: TypeRef): TypeAlias? =
		typeAliases
			.find { it.packageName == ref.packageName && it.name == ref.name }

	fun typeAliasesLookup(): Map<String,TypeAlias> =
		typeAliases
			.associateBy { it.id }

	val typeParameterNames = HashSet<String>()

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
