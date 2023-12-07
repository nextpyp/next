package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.model.Void
import org.jetbrains.dokka.pages.PageNode
import org.jetbrains.dokka.pages.RootPageNode
import org.jetbrains.dokka.transformers.documentation.DocumentableToPageTranslator
import java.nio.file.Paths


class ModelCollector : DocumentableToPageTranslator {

	class Page(
		override val name: String,
		val model: Model
	) : RootPageNode() {

		override val children: List<PageNode>
			get() = emptyList()

		override fun modified(name: String, children: List<PageNode>): RootPageNode =
			Page(name, model)
	}

	override fun invoke(module: DModule): Page {

		// collect all the info we need from the source code
		val model = Model()
		module.children
			.filterIsInstance<DPackage>()
			.forEach { collectPackage(it, model) }

		return Page("the page", model)
	}

	private fun collectPackage(pack: DPackage, model: Model) {

		println("PACKAGE: ${pack.name}")

		// focus only on the services package
		if (pack.name != PACKAGE_SERVICES) {
			return
		}

		// look for services
		pack.classlikes
			.asSequence()
			.filterIsInstance<DInterface>()
			// the tree has tons of duplicates in it for some reason, so filter them out
			.filter { iface -> model.services.none { it.dri == iface.dri } }
			// collect ony services explicitly annotated for export
			.mapNotNull { iface -> iface.exportServiceAnnotation()?.let { iface to it } }
			.map { (iface, export) -> collectService(iface, export) }
			.forEach { model.services.add(it) }

		// TODO: look for type definitions
	}

	private fun collectService(iface: DInterface, export: ExportServiceAnnotation): Model.Service {

		println("\tSERVICE: ${export.name}")

		// collect the service functions
		val functions = iface.children
			.asSequence()
			.filterIsInstance<DFunction>()
			// collect only service functions explicitly annotated for export
			.mapNotNull { func -> func.exportServiceFunctionAnnotation()?.let { func to it } }
			// look for the KVBindingRoute annotation
			.map { (func, export) ->
				val bindingRoute = func.bindingRouteAnnotation()
					?: throw NoSuchElementException("${iface.name}.${func.name} marked for export, but missing KVBindingRoute annotation")
				collectServiceFunction(iface, func, export, bindingRoute)
			}
			.toList()

		return Model.Service(
			dri = iface.dri,
			name = iface.name,
			functions = functions
		)
	}

	private fun collectServiceFunction(
		iface: DInterface,
		func: DFunction,
		export: ExportServiceFunctionAnnotation,
		annotation: BindingRouteAnnotation
	): Model.Service.Function {

		println("\t\tFUNCTION: ${func.name}")

		// read the function arguments
		val arguments = func.parameters
			.map {
				Model.Service.Function.Argument(
					name = it.name
						?: throw NoSuchElementException("${iface.name}.${func.name} argument has no name"),
					type = collectTypeRef(it.type)
				)
			}

		// read the function return, if any
		val returns = func.type
			.takeIf { it != Void }
			?.let { collectTypeRef(it) }

		return Model.Service.Function(
			dri = func.dri,
			mode = Model.Service.Function.Mode.KVision(),
			path = Paths.get("/kv").resolve(annotation.path).toString(),
			arguments = arguments,
			returns = returns
		)
	}

	private fun collectTypeRef(type: Bound): Model.TypeRef =
		when (type) {

			is GenericTypeConstructor ->
				Model.TypeRef(
					packageName = type.dri.packageName ?: "",
					name = type.dri.classNames ?: "",
					params = emptyList() // TODO: need generic params?
				)

			// TODO: handle more types?

			else -> throw Error("don't know how to reference type ${type::class.simpleName}")
		}
}
