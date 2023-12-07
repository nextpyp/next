package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.model.AnnotationTarget
import org.jetbrains.dokka.base.signatures.KotlinSignatureUtils.annotations as rawAnnotations
import org.jetbrains.dokka.model.properties.WithExtraProperties


/**
 * Annotations are source-set dependent in Dokka,
 * but there's only ever one source set,
 * so just get the annotations from the first source set.
 */
fun <D,T> D.annotations(): List<Annotations.Annotation>
	where
		D: Documentable,
		D: WithExtraProperties<T>,
		T: AnnotationTarget
	// sheesh ... what is this, Rust?
{
	return rawAnnotations()
		.values.firstOrNull()
		?.map { it.ensurePackage(this) }
		?: emptyList()
}

fun Annotations.Annotation.ensurePackage(doc: Documentable): Annotations.Annotation {

	// already have a package name? we're good then
	if (dri.packageName?.isNotEmpty() == true) {
		return this
	}

	// but if not, that means the annotation has the same package as the documentable,
	// so put that package in there
	return copy(dri = dri.copy(packageName = doc.dri.packageName))
}


const val PACKAGE_SERVICES = "edu.duke.bartesaghi.micromon.services"


class ExportServiceAnnotation(val name: String)

fun DInterface.exportServiceAnnotation(): ExportServiceAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportService" }
		?.let {
			ExportServiceAnnotation(
				name = it.params.stringOrThrow("name")
			)
		}


class ExportServiceFunctionAnnotation // no annotation properties needed yet

fun DFunction.exportServiceFunctionAnnotation(): ExportServiceFunctionAnnotation? =
	annotations()
		.find { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "ExportServiceFunction" }
		?.let { ExportServiceFunctionAnnotation() }


class BindingRouteAnnotation(val path: String)

fun DFunction.bindingRouteAnnotation(): BindingRouteAnnotation? =
	annotations()
		.find { it.dri.packageName == "io.kvision.annotations" && it.dri.classNames == "KVBindingRoute" }
		?.let {
			BindingRouteAnnotation(
				path = it.params.stringOrThrow("route")
			)
		}


fun Map<String,AnnotationParameterValue>.string(name: String): String? =
	when (val param = this[name]) {
		null -> null
		is StringValue -> param.text()
		else -> throw IllegalStateException("annotation parameter $name is not a string, it's a ${param::class.simpleName}")
	}

fun Map<String,AnnotationParameterValue>.stringOrThrow(name: String): String =
	string(name)
		?: throw NoSuchElementException("anotation has no parameter named $name")
