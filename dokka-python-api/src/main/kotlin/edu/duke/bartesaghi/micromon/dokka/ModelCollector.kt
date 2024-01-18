package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.model.Void
import org.jetbrains.dokka.model.doc.DocTag
import org.jetbrains.dokka.model.doc.DocumentationNode
import org.jetbrains.dokka.model.doc.Text
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

		val packages = module.children
			.filterIsInstance<DPackage>()
			.filter { it.packageName in PACKAGES }

		// pass 1: look for permission info
		packages
			.first { it.packageName == PACKAGE_SERVICES }
			.classlikes
			.filterIsInstance<DEnum>()
			.first { it.name == "AppPermission" }
			.entries
			.forEach { collectPermission(it, model) }

		// pass 2: collect service info
		for (pack in packages) {
			collectPackage(pack, model)
		}

		fun gatherTypes(): Set<String> {

			// limit the loop iteration count, so a bug can't cause infinite iterations
			val maxIterations = 100
			for (i in 0 until maxIterations) {

				// look for the types used by the services
				val serviceTypeRefIds = model.typeRefs()
					.values
					.filter { it.packageName in PACKAGES }
					.map { it.id }
					.toMutableSet()
					.apply {
						// add the realtime message base classes, since they aren't refernced by services directly
						add(Model.typeId(PACKAGE_SERVICES, "RealTimeC2S"))
						add(Model.typeId(PACKAGE_SERVICES, "RealTimeS2C"))
						add(Model.typeId(PACKAGE_SERVICES, "RealTimeS2C.Error"))
					}
				val numAdded = packages
					.asSequence()
					.flatMap { it.classlikes }
					// add the type id
					.map { c -> c to Model.typeId(c.dri) }
					// should be one of the service type refs
					.filter { (_, id) -> id in serviceTypeRefIds }
					// the tree has tons of duplicates in it for some reason, so filter them out
					// also we need to ignore the types we've already seen on later discovery passes
					.filter { (_, id) -> model.types.none { it.id == id } }
					.map { (c, _) -> collectType(c) }
					.count {
						model.types.add(it)
						true
					}
				println("added $numAdded new types in loop iteration $i")

				if (numAdded > 0) {
					continue
				} else {
					return serviceTypeRefIds
				}
			}
			throw Error("gatherTypes() took more than $maxIterations iterations, this is probably a bug?")
		}
		val serviceTypeRefIds = gatherTypes()

		// prune out any extra inner classes we found
		fun purge(type: Model.Type) {
			type.inners.removeIf { inner ->
				(listOf(inner) + inner.descendents())
					.map { it.id }
					.none { it in serviceTypeRefIds }
					.also { if (it) println("removing uneeded type: ${inner.id}") }
			}
			for (inner in type.inners) {
				purge(inner)
			}
		}
		for (type in model.types) {
			purge(type)
		}

		// make sure we found all the type refs
		val typesLookup = model.typesLookup()
		val missingTypeRefIds = serviceTypeRefIds
			.filter { it !in typesLookup }
		if (missingTypeRefIds.isNotEmpty()) {
			throw Error("Types referenced in services but not collected:\n\t${missingTypeRefIds.joinToString("\n\t")}")
		}

		return Page("the page", model)
	}

	private fun collectPermission(perm: DEnumEntry, model: Model) {

		println("PERMISSION: ${perm.name}")

		val annotation = perm.exportPermissionAnnotation()
			?: throw NoSuchElementException("AppPermission ${perm.name} missing ExportPermission annotation")

		model.addPermission(Model.Permission(
			dri = perm.dri,
			appPermissionId = annotation.appPermissionId
		))
	}

	private fun collectPackage(pack: DPackage, model: Model) {

		println("PACKAGE: ${pack.name}")

		// look for services
		pack.classlikes
			.asSequence()
			.filterIsInstance<DInterface>()
			// collect ony services explicitly annotated for export
			.mapNotNull { iface -> iface.exportServiceAnnotation()?.let { iface to it } }
			// the tree has tons of duplicates in it for some reason, so filter them out
			.filter { (iface, _) -> model.services.none { it.dri == iface.dri } }
			.map { (iface, export) -> collectService(iface, export, model::getPermission) }
			.forEach { model.services.add(it) }

		// look for realtime services
		pack.classlikes
			.asSequence()
			.filterIsInstance<DObject>()
			// look for the one realtime services object
			.filter { it.dri.packageName == PACKAGE_SERVICES && it.dri.classNames == "RealTimeServices" }
			.flatMap { it.properties }
			// collect ony services explicitly annotated for export
			.mapNotNull { prop -> prop.exportRealtimeServiceAnnotation()?.let { prop to it } }
			// the tree has tons of duplicates in it for some reason, so filter them out
			.filter { (prop, _) -> model.realtimeServices.none { it.dri == prop.dri } }
			.map { (prop, export) -> collectRealtimeService(prop, export, model::getPermission) }
			.forEach { model.realtimeServices.add(it) }
	}

	private fun collectService(
		iface: DInterface,
		export: ExportServiceAnnotation,
		getPermission: (DRI) -> Model.Permission?
	): Model.Service {

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
				collectServiceFunction(iface, func, export, bindingRoute, getPermission)
			}
			.toList()

		return Model.Service(
			dri = iface.dri,
			name = export.name,
			doc = iface.documentation.readKdoc(),
			functions = functions
		)
	}

	private fun collectServiceFunction(
		iface: DInterface,
		func: DFunction,
		export: ExportServiceFunctionAnnotation,
		annotation: BindingRouteAnnotation,
		getPermission: (DRI) -> Model.Permission?
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

		val permission = getPermission(export.permissionDri)
			?: throw NoSuchElementException("no permission for: ${export.permissionDri}")

		return Model.Service.Function(
			dri = func.dri,
			mode = Model.Service.Function.Mode.KVision(),
			name = func.name,
			path = Paths.get("/kv").resolve(annotation.path).toString(),
			arguments = arguments,
			returns = returns,
			doc = func.documentation.readKdoc(),
			appPermissionId = permission
				.takeIf { !it.isOpen }
				?.appPermissionId
		)
	}

	private fun collectTypeRef(type: Projection): Model.TypeRef =
		when (type) {

			is GenericTypeConstructor ->
				Model.TypeRef(
					packageName = type.dri.packageName ?: "",
					name = type.dri.classNames ?: "",
					params = type.projections
						.map { collectTypeRef(it) }
				)

			is Nullable -> collectTypeRef(type.inner)
				.apply {
					nullable = true
				}
			is Variance<*> -> collectTypeRef(type.inner)

			// do we need to handle more types here?

			else -> throw Error("don't know how to reference type ${type::class.simpleName}")
		}

	private fun collectType(c: DClasslike): Model.Type {

		println("\tTYPE: ${c.dri.classNames}")

		return Model.Type(
			packageName = c.dri.packageName
				?: throw NoSuchElementException("${c::class.simpleName} ${c.dri.classNames} has no package name"),
			name = c.name
				?: throw NoSuchElementException("${c::class.simpleName} ${c.dri.classNames} has no name"),
			props = c.properties
				// filter out explicitly skipped properties
				.filterNot { it.exportServicePropertyAnnotation()?.skip == true }
				//.filter { it.getter == null && it.setter == null }
				.map { collectProperty(it) },
			enumValues = (c as? DEnum)
				?.let { e ->
					e.entries.map { it.name }
				},
			doc = c.documentation.readKdoc(),

			// recurse
			inners = c.classlikes
				// filter out objects
				.filterIsInstance<DClass>()
				.map { collectType(it) }
				.toMutableList()
		).apply {
			inners.forEach { it.outer = this }
		}
	}

	private fun collectProperty(prop: DProperty): Model.Type.Property =
		Model.Type.Property(
			name = prop.name,
			type = collectTypeRef(prop.type),
			doc = prop.documentation.readKdoc()
		)

	private fun collectRealtimeService(
		prop: DProperty,
		export: ExportRealtimeServiceAnnotation,
		getPermission: (DRI) -> Model.Permission?
	): Model.RealtimeService {

		println("\tREALTIME SERVICE: ${export.name}")

		fun DRI.toTypeRef() = Model.TypeRef(
			packageName = packageName
				?: throw Error("Realtime service message has no package name: $this"),
			name = classNames
				?: throw Error("Realtime service message has no class names: $this"),
			params = emptyList()
		)

		val permission = getPermission(export.permissionDri)
			?: throw NoSuchElementException("no permission for: ${export.permissionDri}")

		return Model.RealtimeService(
			dri = prop.dri,
			name = export.name,
			path = "/ws/${prop.name}",
			messagesC2S = export.messagesC2S
				.map { it.toTypeRef() },
			messagesS2C = export.messagesS2C
				.map { it.toTypeRef() },
			doc = prop.documentation.readKdoc(),
			appPermissionId = permission
				.takeIf { !it.isOpen }
				?.appPermissionId
		)
	}

	private fun SourceSetDependent<DocumentationNode>.readKdoc(): Model.Doc? {

		fun DocTag.collectText(): List<String> =
			when (this) {

				// only get the text nodes for now
				is Text -> listOf(body)

				// just recurse through everything else looking for Text nodes
				else -> children.flatMap { it.collectText() }
			}

		return values
			.flatMap { node ->
				node.children
					.flatMap { it.root.collectText() }
			}
			.takeIf { it.isNotEmpty() }
			?.let { Model.Doc(it.joinToString("\n")) }
	}
}
