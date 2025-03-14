package edu.duke.bartesaghi.micromon.dokka

import org.jetbrains.dokka.base.templating.parseJson
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.*
import org.jetbrains.dokka.model.Void
import org.jetbrains.dokka.model.doc.*
import org.jetbrains.dokka.pages.PageNode
import org.jetbrains.dokka.pages.RootPageNode
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.transformers.documentation.DocumentableToPageTranslator
import java.nio.file.Paths


class ModelCollector(val ctx: DokkaContext) : DocumentableToPageTranslator {

	class Page(
		override val name: String,
		val model: Model
	) : RootPageNode() {

		override val children: List<PageNode>
			get() = emptyList()

		override fun modified(name: String, children: List<PageNode>): RootPageNode =
			Page(name, model)
	}

	data class Config(
		val apiVersion: String
	)

	override fun invoke(module: DModule): Page {

		// read the plugin config
		val config = ctx.configuration.pluginsConfiguration
			.firstOrNull { it.fqPluginName == MicromonDokkaPlugin::class.qualifiedName }
			?.let { parseJson<Config>(it.values) }
			?: throw NoSuchElementException("no plugin configuration given")
		println("Generating API v${config.apiVersion} ...")

		// collect all the info we need from the source code
		val model = Model(config.apiVersion)

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
					.filter { it.packageName in PACKAGES }
					.map { it.id }
					.toMutableSet()
					.apply {
						// add the realtime message base classes, since they aren't referenced by services directly
						add(Model.typeId(PACKAGE_SERVICES, "RealTimeC2S"))
						add(Model.typeId(PACKAGE_SERVICES, "RealTimeS2C"))
						add(Model.typeId(PACKAGE_SERVICES, "RealTimeS2C.Error"))
					}

				var numAdded = 0

				// look for types
				numAdded += packages
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

				// look for type aliases
				numAdded += packages
					.asSequence()
					.flatMap { it.typealiases }
					// add the type id
					.map { c -> c to Model.typeId(c.dri) }
					// should be one of the service type refs
					.filter { (_, id) -> id in serviceTypeRefIds }
					// the tree has tons of duplicates in it for some reason, so filter them out
					// also we need to ignore the types we've already seen on later discovery passes
					.filter { (_, id) -> model.typeAliases.none { it.id == id } }
					.map { (t, _) -> collectTypeAlias(t) }
					.count {
						model.typeAliases.add(it)
						true
					}

				println("added $numAdded new types and alises in loop iteration $i")

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
			type.inners.removeIf r@{ inner ->

				// don't prune out polymorphic serialization classes,
				// since they could still be used but not directly referenced in signatures
				if (inner.polymorphicSerialization) {
					return@r false
				}

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

		// add referenced types that aren't in our source code
		collectExternalTypes(model)
		val typesLookup = model.typesLookup()
		val typeAliasesLookup = model.typeAliasesLookup()

		// make sure we found types for all the type refs
		val missingTypeRefIds = serviceTypeRefIds
			.filter { it !in typesLookup && it !in typeAliasesLookup }
		if (missingTypeRefIds.isNotEmpty()) {
			throw Error("Types referenced in services but not collected:\n\t${missingTypeRefIds.joinToString("\n\t")}")
		}

		// collect types for the type parameters
		for (ref in model.typeRefs()) {

			val alias = model.findTypeAlias(ref)
			if (alias != null) {
				for ((paramRef, param) in ref.params zip alias.typeParams) {
					param.instances.add(paramRef)
				}
			}

			val resolvedRef = ref.resolveAliases()
			resolvedRef.params
				.takeIf { resolvedRef.packageName in PACKAGES && it.isNotEmpty() }
				?.let l@{ paramRefs ->
					val type = typesLookup[resolvedRef.id]
						?: throw Error("no type for ref $resolvedRef")
					for ((paramRef, param) in paramRefs zip type.typeParams) {
						param.instances.add(paramRef)
					}
				}
		}

		// collect the unique type parameter names (eg T)
		for (type in model.types) {
			for (typeParam in type.typeParams) {
				model.typeParameterNames.add(typeParam.name)
			}
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
			.mapNotNull {
				Model.Service.Function.Argument(
					name = it.name
						?: throw NoSuchElementException("${iface.name}.${func.name} argument has no name"),
					type = collectTypeRef(it.type) ?: return@mapNotNull null
				)
			}

		// read the function return, if any
		val returns = func.type
			.takeIf { it != Void && !(it is GenericTypeConstructor && it.dri.packageName == "kotlin" && it.dri.classNames == "Unit") }
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

	private fun collectTypeRef(type: Projection): Model.TypeRef? =
		when (type) {

			is GenericTypeConstructor ->
				Model.TypeRef(
					packageName = type.dri.packageName ?: "",
					name = type.dri.classNames ?: "",
					params = type.projections
						.mapNotNull { collectTypeRef(it) }
				)

			is Nullable -> collectTypeRef(type.inner)
				?.copy(nullable = true)

			is Variance<*> -> collectTypeRef(type.inner)

			is TypeAliased -> collectTypeRef(type.typeAlias)
				?.copy(aliased = collectTypeRef(type.inner))

			is TypeParameter ->
				Model.TypeRef(
					packageName = "",
					name = type.name,
					parameter = true
				)

			is FunctionalTypeConstructor -> {
				println("WARNING: function object not supported")
				null
			}
			// do we need to handle more types here?

			else -> throw Error("don't know how to reference type ${type::class.simpleName} for $type")
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
				.mapNotNull { collectProperty(it) },
			typeParams =
				when (c) {
					is DClass -> c.generics.map { it.name }
					is DInterface -> c.generics.map { it.name }
					else -> emptyList()
				}
				.map { Model.Type.Param(it) },
			enumValues = (c as? DEnum)
				?.let { e ->
					e.entries.map { it.name }
				},
			polymorphicSerialization = when (c) {
				is DClass -> c.exportClassAnnotation()?.polymorphicSerialization ?: false
				else -> false
			},
			doc = c.documentation.readKdoc(),

			// recurse
			inners = c.classlikes
				// filter out objects
				.filter { it is DClass || it is DInterface }
				.map { collectType(it) }
				.toMutableList()
		).apply {
			inners
				.forEach { it.outer = this }
			inners
				.filter { it.polymorphicSerialization }
				.forEach {
					polymorphicSubtypes.add(it)
					it.polymorphicSupertype = this
				}
		}
	}

	private fun collectProperty(prop: DProperty): Model.Type.Property? =
		collectTypeRef(prop.type)?.let { propType ->
			Model.Type.Property(
				name = prop.name,
				type = propType,
				default = prop.extra[DefaultValue]?.value,
				doc = prop.documentation.readKdoc()
			)
		}

	private fun collectTypeAlias(t: DTypeAlias): Model.TypeAlias {

		println("\tTYPEALIAS: ${t.dri.classNames}")

		return Model.TypeAlias(
			packageName = t.dri.packageName ?: "",
			name = t.dri.classNames ?: "",
			typeParams = t.generics.map { Model.Type.Param(it.name) },
			ref = t.underlyingType.values
				.firstOrNull()
				?.let { collectTypeRef(it) }
				?: throw Error("Type alias ${t.dri} has no underlying type")
		)
	}

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

	private fun DocTag.renderRst(out: StringBuilder) {

		fun recurse() = children.forEach { it.renderRst(out) }
		fun write(text: String) = out.append(text)
		fun writeln() {
			write("\n")
		}
		fun writeln(line: String) {
			write(line)
			writeln()
		}

		when (this) {

			is Text -> write(body)

			is P -> {
				recurse()
				writeln()
			}

			is CodeInline -> {
				write("``")
				recurse()
				write("``")
			}

			is Ul -> {
				writeln()
				recurse()
			}

			is Li -> {
				write("* ")
				recurse()
			}

			// TODO: need to support any other HTML tags?

			is CustomDocTag -> when (name) {
				"MARKDOWN_FILE" -> recurse()
				else -> throw UnsupportedOperationException("don't know how to handle custom doc tag: $name")
			}
			else -> throw UnsupportedOperationException("don't know how to handle doc tag: ${this::class.simpleName}")
		}
	}

	private fun SourceSetDependent<DocumentationNode>.readKdoc(): Model.Doc? =
		values
			.flatMap { node ->
				node.children.map { wrapper ->
					val buf = StringBuilder()
					wrapper.root.renderRst(buf)
					buf.toString()
				}
			}
			.takeIf { it.isNotEmpty() }
			?.let { Model.Doc(it.joinToString("\n")) }

	private fun collectExternalTypes(model: Model) {

		model.externalTypes.add(Model.Type(
			"io.kvision.remote",
			"RemoteOption",
			listOf(
				Model.Type.Property("value", Model.TypeRef("kotlin", "String", nullable = true)),
				Model.Type.Property("text", Model.TypeRef("kotlin", "String", nullable = true)),
				Model.Type.Property("className", Model.TypeRef("kotlin", "String", nullable = true)),
				Model.Type.Property("subtext", Model.TypeRef("kotlin", "String", nullable = true)),
				Model.Type.Property("icon", Model.TypeRef("kotlin", "String", nullable = true)),
				Model.Type.Property("content", Model.TypeRef("kotlin", "String", nullable = true)),
				Model.Type.Property("disabled", Model.TypeRef("kotlin", "Boolean"), default = BooleanConstant(false)),
				Model.Type.Property("divider", Model.TypeRef("kotlin", "Boolean"), default = BooleanConstant(false))
			),
			doc = Model.Doc("""
				|Search result option, with some options for presentation
			""".trimMargin())
		))
	}
}
