package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.model.AnnotationTarget
import org.jetbrains.dokka.model.properties.PropertyContainer
import org.jetbrains.dokka.base.signatures.KotlinSignatureUtils.annotations as rawAnnotations
import org.jetbrains.dokka.model.properties.WithExtraProperties
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties


/**
 * Annotations are source-set dependent in Dokka,
 * but there's only ever one source set,
 * so just get the annotations from the first source set.
 */
fun <T:AnnotationTarget> WithExtraProperties<T>.annotations(): List<Annotations.Annotation> =
	rawAnnotations()
		.values.firstOrNull()
		?: emptyList()


data class ExportServiceAnnotation(val name: String)

fun DInterface.exportServiceAnnotation(): ExportServiceAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportService" }
		?.let {
			ExportServiceAnnotation(
				name = it.params.stringOrThrow("name")
			)
		}


data class ExportServiceFunctionAnnotation(
	val permissionDri: DRI
)

fun DFunction.exportServiceFunctionAnnotation(): ExportServiceFunctionAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportServiceFunction" }
		?.let {
			ExportServiceFunctionAnnotation(
				permissionDri = it.params.enumOrThrow("permission")
			)
		}


data class ExportServicePropertyAnnotation(
	val skip: Boolean,
	val default: AnnotatedDefaultValue? = null
)

enum class AnnotatedDefaultValue {
	EmptyList
}

fun DProperty.exportServicePropertyAnnotation(): ExportServicePropertyAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportServiceProperty" }
		?.let {
			ExportServicePropertyAnnotation(
				skip = it.params.boolean("skip") ?: false,
				default = it.params.enum("default")
					?.let { dri ->
						when (Model.typeId(dri)) {
							Model.typeId(PACKAGE_SERVICES, "DefaultValue.None") -> null
							Model.typeId(PACKAGE_SERVICES,"DefaultValue.EmptyList") -> AnnotatedDefaultValue.EmptyList
							else -> throw Error("Unrecognized annotated default value type: $dri")
						}
					}
			)
		}


data class ExportRealtimeServiceAnnotation(
	val name: String,
	val permissionDri: DRI,
	val messagesC2S: List<DRI>,
	val messagesS2C: List<DRI>
)

fun DProperty.exportRealtimeServiceAnnotation(): ExportRealtimeServiceAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportRealtimeService" }
		?.let {
			ExportRealtimeServiceAnnotation(
				name = it.params.stringOrThrow("name"),
				permissionDri = it.params.enumOrThrow("permission"),
				messagesC2S = it.params.classListOrThrow("messagesC2S"),
				messagesS2C = it.params.classListOrThrow("messagesS2C")
			)
		}


data class BindingRouteAnnotation(val path: String)

fun DFunction.bindingRouteAnnotation(): BindingRouteAnnotation? =
	annotations()
		.find { it.dri.packageName == "io.kvision.annotations" && it.dri.classNames == "KVBindingRoute" }
		?.let {
			BindingRouteAnnotation(
				path = it.params.stringOrThrow("route")
			)
		}


data class ExportPermissionAnnotation(val appPermissionId: String)

fun DEnumEntry.exportPermissionAnnotation(): ExportPermissionAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportPermission" }
		?.let {
			ExportPermissionAnnotation(
				appPermissionId = it.params.stringOrThrow("appPermissionId")
			)
		}


data class ExportClassAnnotation(val polymorphicSerialization: Boolean)

fun DClass.exportClassAnnotation(): ExportClassAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportClass" }
		?.let {
			ExportClassAnnotation(
				polymorphicSerialization = it.params.booleanOrThrow("polymorphicSerialization")
			)
		}


inline fun <reified T> Map<String,AnnotationParameterValue>.param(name: String): T? =
	when (val param = this[name]) {
		null -> null
		is T -> param
		else -> throw IllegalStateException("annotation parameter $name is not a ${T::class.simpleName}, it's a ${param::class.simpleName}")
	}

fun Map<String,AnnotationParameterValue>.string(name: String): String? =
	param<StringValue>(name)
		?.value

fun Map<String,AnnotationParameterValue>.stringOrThrow(name: String): String =
	string(name)
		?: throw NoSuchElementException("anotation has no string parameter named $name, try one of ${dumpParams()}")


fun Map<String,AnnotationParameterValue>.boolean(name: String): Boolean? =
	param<BooleanValue>(name)
		?.value

fun Map<String,AnnotationParameterValue>.booleanOrThrow(name: String): Boolean =
	boolean(name)
		?: throw NoSuchElementException("anotation has no boolean parameter named $name, try one of ${dumpParams()}")


fun Map<String,AnnotationParameterValue>.classList(name: String): List<DRI>? =
	param<ArrayValue>(name)
		?.value
		?.filterIsInstance<ClassValue>()
		?.map { it.classDRI }

fun Map<String,AnnotationParameterValue>.classListOrThrow(name: String): List<DRI> =
	classList(name)
		?: throw NoSuchElementException("anotation has no class list parameter named $name, try one of ${dumpParams()}")


fun Map<String,AnnotationParameterValue>.enum(name: String): DRI? =
	param<EnumValue>(name)
		?.enumDri

fun Map<String,AnnotationParameterValue>.enumOrThrow(name: String): DRI =
	enum(name)
		?: throw NoSuchElementException("annotation has no enum parameter named $name, try one of ${dumpParams()}")

fun Map<String,AnnotationParameterValue>.dumpParams(): String =
	entries.joinToString(",") { (k, v) -> "$k=${v::class.simpleName}" }


// because the default toString() methods for dokka classes are completely damn useless!!! >8[

fun nest(str: String?, indent: Int): String? {
	val indentStr = (0 until indent*4).joinToString("") { " " }
	return str
		?.replace("\n", "\n$indentStr")
}

fun dump(indent: Int, name: String, vals: List<Pair<String,String?>>): String {

	val buf = StringBuilder()

	fun indent(indent: Int) {
		for (i in 0 until indent*4) {
			buf.append(' ')
		}
	}

	indent(indent)
	buf.append(name)
	buf.append("[\n")

	for ((key, value) in vals) {
		indent(indent + 1)
		buf.append("$key = ${nest(value, indent + 1)}\n")
	}

	indent(indent)
	buf.append("]")

	return buf.toString()
}

fun <T> dumpList(list: Collection<T>, multiline: Boolean, dumper: (T) -> String): String {

	val buf = StringBuilder()

	buf.append("[")
	if (multiline) {
		buf.append("\n")
	}

	for ((i, item) in list.withIndex()) {
		if (multiline) {
			buf.append("\t")
		}
		buf.append(nest(dumper(item), 1))
		if (i > 0) {
			buf.append(", ")
		}
		if (multiline) {
			buf.append("\n")
		}
	}

	buf.append("]")

	return buf.toString()
}

fun Documentable.dump(): String =
	when (this) {
		is DFunction -> dump()
		// more?
		else -> "Documentable->${this::class.simpleName}"
	}

fun DFunction.dump(indent: Int = 0): String =
	dump(indent, "DFunction", listOf(
		"dri" to dri.toString(),
		"name" to name,
		"isConstructor" to isConstructor.toString(),
		"parameters" to dumpList(parameters, true) { it.dump() },
		"visibility" to dumpList(visibility.values, false) { it.toString() },
		"type" to type.dump(),
		"generics" to dumpList(generics, true) { it.dump() },
		"receiver" to receiver?.dump(),
		"modifier" to dumpList(modifier.values, false) { it.toString() },
		"isExpectActual" to isExpectActual.toString(),
		"extra" to extra.dump(),
		"children" to dumpList(children, true) { it.dump() }
	))

fun DProperty.dump(indent: Int = 0): String =
	dump(indent, "DProperty", listOf(
		"dri" to dri.toString(),
		"name" to name,
		"visibility" to dumpList(visibility.values, false) { it.toString() },
		"type" to type.dump(),
		"receiver" to receiver?.dump(),
		"setter" to setter?.dump(),
		"getter" to getter?.dump(),
		"modifier" to dumpList(modifier.values, false) { it.toString() },
		"generics" to dumpList(generics, true) { it.dump() },
		"isExpectActual" to isExpectActual.toString(),
		"extra" to extra.dump()
	))

fun DParameter.dump(indent: Int = 0): String =
	dump(indent, "DParameter", listOf(
		"dri" to dri.toString(),
		"name" to name
		// more?
	))

fun DTypeParameter.dump(indent: Int = 0): String =
	dump(indent, "DTypeParameter", listOf(
		"dri" to dri.toString(),
		"name" to name
		// more?
	))

fun Bound.dump(): String =
	"Bound[$this]"

fun PropertyContainer<*>.dump(indent: Int = 0): String {

	// drop the type parameter variance, since it apparently keeps us from reading property values
	val c = this::class as KClass<PropertyContainer<*>>

	// break encapsulation to get the inner map to we can enumerate keys/values
	val mapProp = c.declaredMemberProperties
		.find { it.name == "map" }
		?: throw Error("no map property")
	val map = mapProp(this) as Map<*,*>

	return dump(indent, "PropertyContainer", map.entries.map { (key, value) ->
		val keyName =
			if (key == null) {
				"(null)"
			} else {
				key::class.qualifiedName ?: "(key class has no name)"
			}
		keyName to when (value) {
			null -> "(null)"
			is AdditionalModifiers -> dumpList(value.content.values, true) { it.toString() }
			is Annotations -> dumpList(value.content.values, true) { annotations ->
				dumpList(annotations, true) { it.toString() }
			}
			is DefaultValue -> value.value.toString()
			else -> "UnrecognizedValue[${value::class.qualifiedName ?: "(none)"}]"
		}
	})
}
