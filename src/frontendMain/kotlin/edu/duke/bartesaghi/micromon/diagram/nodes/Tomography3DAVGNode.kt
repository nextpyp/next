package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.Tomography3DAVGNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.revalidateDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.Viewport
import edu.duke.bartesaghi.micromon.views.Tomography3DAVGView
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType
import io.kvision.form.formPanel
import io.kvision.modal.Modal


class Tomography3DAVGNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: Tomography3DAVGData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as Tomography3DAVGData

	companion object : NodeClientInfo {

		override val config = Tomography3DAVGNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-globe") // TODO: pick an icon
		override val jobClass = Tomography3DAVGData::class
		override val urlFragment = "Tomography3DAVG"

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			Tomography3DAVGNode(viewport, diagram, project, job as Tomography3DAVGData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as Tomography3DAVGNode?)?.job?.args
				?: JobArgs.fromNext(Tomography3DAVGArgs(newArgValues(project, input)))
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomography3DAVG.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(Tomography3DAVGNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = Tomography3DAVGArgs(
				values = argValues
			)
			return Services.tomography3DAVG.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): Tomography3DAVGData =
			Services.tomography3DAVG.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomography3DAVG.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<Tomography3DAVGArgs>?, enabled: Boolean, onDone: (Tomography3DAVGArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<Tomography3DAVGArgs>().apply {
				add(Tomography3DAVGArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
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
				// TODO: make a view
				Tomography3DAVGView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName, width = 256)
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
					baseJob = Services.tomography3DAVG.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
