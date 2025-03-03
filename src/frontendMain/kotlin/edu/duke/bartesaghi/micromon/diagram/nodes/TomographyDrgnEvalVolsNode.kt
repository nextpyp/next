package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographyDrgnEvalVolsNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.revalidateDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyDrgnEvalVolsView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyDrgnEvalVolsNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyDrgnEvalVolsData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyDrgnEvalVolsData

	companion object : NodeClientInfo {

		override val config = TomographyDrgnEvalVolsNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-dragon")
		override val jobClass = TomographyDrgnEvalVolsData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyDrgnEvalVolsNode(viewport, diagram, project, job as TomographyDrgnEvalVolsData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyDrgnEvalVolsNode?)?.job?.args
				?: JobArgs.fromNext(TomographyDrgnEvalVolsArgs(newArgValues(project, input)))
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyDrgnEvalVols.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyDrgnEvalVolsNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyDrgnEvalVolsArgs(
				values = argValues
			)
			return Services.tomographyDrgnEvalVols.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographyDrgnEvalVolsData =
			Services.tomographyDrgnEvalVols.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyDrgnEvalVols.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyDrgnEvalVolsArgs>?, enabled: Boolean, onDone: (TomographyDrgnEvalVolsArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyDrgnEvalVolsArgs>().apply {
				add(TomographyDrgnEvalVolsArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			form.init(args)
			if (enabled) {
				win.addSaveResetButtons(form, args, onDone)
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
				TomographyDrgnEvalVolsView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName, width = 256)
				// NOTE: this image is SVG, so it needs an explicit size
			}
		}

		if (refreshImages) {
			getHTMLElement()?.revalidateDynamicImages()
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
					baseJob = Services.tomographyDrgnEvalVols.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
