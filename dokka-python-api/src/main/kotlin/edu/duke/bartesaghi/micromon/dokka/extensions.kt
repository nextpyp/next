package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.model.AnnotationTarget
import org.jetbrains.dokka.base.signatures.KotlinSignatureUtils.annotations as rawAnnotations
import org.jetbrains.dokka.model.properties.WithExtraProperties


/**
 * Annotations are source-set dependent in Dokka,
 * but there's only ever one source set,
 * so just get the annotations from the first source set.
 */
fun <T:AnnotationTarget> WithExtraProperties<T>.annotations(): List<Annotations.Annotation> =
	rawAnnotations()
		.values.firstOrNull()
		?: emptyList()


class ExportServiceAnnotation(val name: String)

fun DInterface.exportServiceAnnotation(): ExportServiceAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportService" }
		?.let {
			ExportServiceAnnotation(
				name = it.params.stringOrThrow("name")
			)
		}


class ExportServiceFunctionAnnotation(
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


class ExportServicePropertyAnnotation(val skip: Boolean)

fun DProperty.exportServicePropertyAnnotation(): ExportServicePropertyAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportServiceProperty" }
		?.let {
			ExportServicePropertyAnnotation(
				skip = it.params.booleanOrThrow("skip")
			)
		}


class ExportRealtimeServiceAnnotation(
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


class BindingRouteAnnotation(val path: String)

fun DFunction.bindingRouteAnnotation(): BindingRouteAnnotation? =
	annotations()
		.find { it.dri.packageName == "io.kvision.annotations" && it.dri.classNames == "KVBindingRoute" }
		?.let {
			BindingRouteAnnotation(
				path = it.params.stringOrThrow("route")
			)
		}


class ExportPermissionAnnotation(val appPermissionId: String)

fun DEnumEntry.exportPermissionAnnotation(): ExportPermissionAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportPermission" }
		?.let {
			ExportPermissionAnnotation(
				appPermissionId = it.params.stringOrThrow("appPermissionId")
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
		?: throw NoSuchElementException("anotation has no parameter named $name")


fun Map<String,AnnotationParameterValue>.boolean(name: String): Boolean? =
	param<BooleanValue>(name)
		?.value

fun Map<String,AnnotationParameterValue>.booleanOrThrow(name: String): Boolean =
	boolean(name)
		?: throw NoSuchElementException("anotation has no parameter named $name")


fun Map<String,AnnotationParameterValue>.classList(name: String): List<DRI>? =
	param<ArrayValue>(name)
		?.value
		?.filterIsInstance<ClassValue>()
		?.map { it.classDRI }

fun Map<String,AnnotationParameterValue>.classListOrThrow(name: String): List<DRI> =
	classList(name)
		?: throw NoSuchElementException("anotation has no parameter named $name")


fun Map<String,AnnotationParameterValue>.enum(name: String): DRI? =
	param<EnumValue>(name)
		?.enumDri

fun Map<String,AnnotationParameterValue>.enumOrThrow(name: String): DRI =
	enum(name)
		?: throw NoSuchElementException("annotation has no parameter named $name")
