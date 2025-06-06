package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.ArgsForm
import edu.duke.bartesaghi.micromon.components.forms.addSaveResetButtons
import edu.duke.bartesaghi.micromon.components.forms.init
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.SingleParticleMaskingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.revalidateDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.IntegratedRefinementView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class SingleParticleMaskingNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: SingleParticleMaskingData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as SingleParticleMaskingData

	companion object : NodeClientInfo {

		override val config = SingleParticleMaskingNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-crop")
		override val jobClass = SingleParticleMaskingData::class
		override val urlFragment = "singleParticleMasking"

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			SingleParticleMaskingNode(viewport, diagram, project, job as SingleParticleMaskingData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as SingleParticleMaskingNode?)?.job?.args
				?: JobArgs.fromNext(SingleParticleMaskingArgs(newArgValues(project, input)))
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.singleParticleMasking.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(SingleParticleMaskingNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = SingleParticleMaskingArgs(
				values = argValues
			)
			return Services.singleParticleMasking.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): SingleParticleMaskingData =
			Services.singleParticleMasking.get(jobId)


		override val pypArgs = ServerVal {
			Args.fromJson(Services.singleParticleMasking.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<SingleParticleMaskingArgs>?, enabled: Boolean, onDone: (SingleParticleMaskingArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<SingleParticleMaskingArgs> {
				add(SingleParticleMaskingArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
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
			button(className = "image-button reconstruction-node", onClick = {
				IntegratedRefinementView.go(viewport, project, job)
			}) {
				img(job.imageURL, className = dynamicImageClassName)
			}
			div {
				text("Masked map")
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
					baseJob = Services.singleParticleMasking.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
