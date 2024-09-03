package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographyDenoisingTrainingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyDenoisingTrainingView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.select.SelectRemote
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyDenoisingTrainingNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyDenoisingTrainingData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyDenoisingTrainingData

	companion object : NodeClientInfo {

		override val config = TomographyDenoisingTrainingNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-hand-sparkles")
		override val jobClass = TomographyDenoisingTrainingData::class
		override val urlFragment = "tomographyDenoisingTraining"

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyDenoisingTrainingNode(viewport, diagram, project, job as TomographyDenoisingTrainingData)

		override fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, andCopyData: Boolean, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyDenoisingTrainingNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyDenoisingTraining.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyDenoisingTrainingNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyDenoisingTrainingArgs(
				values = argValues,
				filter = null
			)
			return Services.tomographyDenoisingTraining.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographyDenoisingTrainingData =
			Services.tomographyDenoisingTraining.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyDenoisingTraining.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyDenoisingTrainingArgs>?, enabled: Boolean, onDone: (TomographyDenoisingTrainingArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyDenoisingTrainingArgs>().apply {

				add(TomographyDenoisingTrainingArgs::filter,
					SelectRemote(
						serviceManager = BlocksServiceManager,
						function = IBlocksService::filterOptions,
						stateFunction = { upstreamNode.jobId },
						label = "Filter tomograms",
						preload = true
					)
				)

				add(TomographyDenoisingTrainingArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// use the none filter option for the particles name in the form,
			// since the control can't handle nulls
			val mapper = ArgsMapper<TomographyDenoisingTrainingArgs>(
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
			val argsOrCopy: JobArgs<TomographyDenoisingTrainingArgs> = args
				?: JobArgs.fromNext(TomographyDenoisingTrainingArgs(
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
			button(className = "image-button reconstruction-node", onClick = {
				TomographyDenoisingTrainingView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName)
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
					baseJob = Services.tomographyDenoisingTraining.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
