package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.nodes.TomographyPickingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyPickingView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.select.SelectRemote
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


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

		override fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyPickingNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyPicking.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyPickingNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyPickingArgs(
				values = argValues,
				filter = null
			)
			return Services.tomographyPicking.addNode(project.owner.id, project.projectId, input, args)
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

			form.init(argsOrCopy)
			if (enabled) {
				win.addSaveResetButtons(form, argsOrCopy, onDone)
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
