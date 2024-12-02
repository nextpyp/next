package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.nodes.TomographyPickingOpenNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyPickingOpenView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyPickingOpenNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyPickingOpenData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyPickingOpenData

	companion object : NodeClientInfo {

		override val config = TomographyPickingOpenNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-crosshairs")
		override val jobClass = TomographyPickingOpenData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyPickingOpenNode(viewport, diagram, project, job as TomographyPickingOpenData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyPickingOpenNode?)?.job?.args
				?: JobArgs.fromNext(TomographyPickingOpenArgs(newArgValues(project, input)))
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyPickingOpen.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyPickingOpenNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyPickingOpenArgs(
				values = argValues
			)
			return Services.tomographyPickingOpen.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographyPickingOpenData =
			Services.tomographyPickingOpen.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyPickingOpen.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyPickingOpenArgs>?, enabled: Boolean, onDone: (TomographyPickingOpenArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyPickingOpenArgs>().apply {
				add(TomographyPickingOpenArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
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
				TomographyPickingOpenView.go(viewport, project, job)
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
					baseJob = Services.tomographyPickingOpen.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
