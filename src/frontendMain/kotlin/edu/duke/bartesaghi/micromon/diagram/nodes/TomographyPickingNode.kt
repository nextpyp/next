package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.nodes.TomographyPickingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyPickingView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.select.SelectRemote
import io.kvision.core.onEvent
import io.kvision.form.check.CheckBox
import io.kvision.form.formPanel
import io.kvision.html.Button
import io.kvision.html.div
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


class TomographyPickingNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyPickingData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyPickingData

	companion object : NodeClientInfo {

		override val config = TomographyPickingNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-crosshairs")
		override val jobClass = TomographyPickingData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyPickingNode(viewport, diagram, project, job as TomographyPickingData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {

			// handle copying from the source node
			val srcNode = copyFrom as TomographyPickingNode?
			var jobArgs: JobArgs<TomographyPickingArgs>? = null
			var copyArgs: TomographyPickingCopyArgs? = null

			if (srcNode != null) {
				var args = srcNode.job.args.newest()?.args
				if (args != null) {

					// ask the user to pick args
					copyArgs = showCopyForm(srcNode)

					// if we're copying the particles to manual, also change the detect method to manual
					if (copyArgs.copyParticlesToManual) {
						val values = args.values.toArgValues(pypArgs.get())
						values.tomoPickMethod = TomoPickMethod.Manual
						args = args.copy(values.toToml())
					}

					jobArgs = JobArgs.fromNext(args)
				}
			}

			form(config.name, outNode, jobArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyPicking.addNode(project.owner.id, project.projectId, input, args, copyArgs)

					// send the node back to the diagram
					callback(TomographyPickingNode(viewport, diagram, project, data))
				}
			}
		}

		private suspend fun showCopyForm(src: Node): TomographyPickingCopyArgs = suspendCoroutine { continuation ->

			val win = Modal(
				caption = "Copy Block: ${config.name}",
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup")
			)

			val form = win.formPanel<TomographyPickingCopyArgs>().apply {

				val dataCheck = CheckBox(
					value = false,
					label = "Copy files and data"
				)
				val particlesCheck = CheckBox(
					value = false,
					label = "Make automatically-picked particles editable"
				)

				// make the particles check only enabled iff copy data is checked
				particlesCheck.enabled = false
				dataCheck.onEvent {
					change = {
						if (dataCheck.value) {
							particlesCheck.enabled = true
						} else {
							particlesCheck.enabled = false
							particlesCheck.value = false
						}
					}
				}

				add(TomographyPickingCopyArgs::copyFromJobId, HiddenString(src.jobId))
				add(TomographyPickingCopyArgs::copyData, dataCheck)
				add(TomographyPickingCopyArgs::copyParticlesToManual, particlesCheck)
			}

			win.div(classes = setOf("spaced")) {
				content = (
					"NOTE: When copying files and data,"
					+ " if this block has a lot of files to copy,"
					+ " the copy process could take several minutes,"
					+ " depending on the speed of your file system"
				)
			}

			win.addButton(Button("Next").onClick {
				win.hide()
				continuation.resume(form.getData())
			})

			win.show()
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyPickingArgs(
				values = argValues,
				filter = null
			)
			return Services.tomographyPicking.addNode(project.owner.id, project.projectId, input, args, null)
		}

		override suspend fun getJob(jobId: String): TomographyPickingData =
			Services.tomographyPicking.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyPicking.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyPickingArgs>?, enabled: Boolean, onDone: (TomographyPickingArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyPickingArgs>().apply {

				val upstreamIsPreprocessing =
					upstreamNode is TomographyPreprocessingNode
					|| upstreamNode is TomographyPurePreprocessingNode
					|| upstreamNode is TomographyImportDataNode
					|| upstreamNode is TomographySessionDataNode
					|| upstreamNode is TomographyRelionDataNode

				add(TomographyPickingArgs::filter,
					// look for the preprocessing job in the upstream node to get the filter
					if (upstreamIsPreprocessing) {
						SelectRemote(
							serviceManager = BlocksServiceManager,
							function = IBlocksService::filterOptions,
							stateFunction = { upstreamNode.jobId },
							label = "Filter tomograms",
							preload = true
						)
					} else {
						HiddenString()
					}
				)
				add(TomographyPickingArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// use the none filter option for the particles name in the form,
			// since the control can't handle nulls
			val mapper = ArgsMapper<TomographyPickingArgs>(
				toForm = { args ->
					if (args.filter == null) {
						args.copy(filter = NoneFilterOption)
					} else {
						args
					}
				},
				fromForm = { args ->
					if (args.filter == NoneFilterOption) {
						args.copy(filter = null)
					} else {
						args
					}
				}
			)
			
			// by default, copy args values from the upstream node
			val argsOrCopy: JobArgs<TomographyPickingArgs> = args
				?: JobArgs.fromNext(TomographyPickingArgs(
					filter = null,
					values = upstreamNode.newestArgValues()?.filterForDownstreamCopy(pypArgs) ?: ""
				))

			form.init(argsOrCopy, mapper)
			if (enabled) {
				win.addSaveResetButtons(form, argsOrCopy, mapper, onDone)
			}
			win.show()
		}
	}

	init {
		render()
	}

	override fun renderContent(refreshImages: Boolean) {

		content {
			button(className = "image-button", onClick = {
				TomographyPickingView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName)
			}
			div(className = "count") {
				text("${job.numParticles.formatWithDigitGroupsSeparator()} particles")
			}
		}

		if (refreshImages) {
			getHTMLElement()?.refreshDynamicImages()
		}
	}

	override fun onRunInit() {
		job.args.run()
	}

	override fun onEdit(enabled: Boolean) {
		form(job.numberedName, getUpstreamNodeOrThrow(), job.args, enabled) { newArgs ->

			// save the edits if needed
			val diff = job.args.diff(newArgs)
			if (diff.shouldSave) {
				AppScope.launch {
					baseJob = Services.tomographyPicking.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
