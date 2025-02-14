package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.ArgsForm
import edu.duke.bartesaghi.micromon.components.forms.addSaveResetButtons
import edu.duke.bartesaghi.micromon.components.forms.init
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographyFineRefinementNodeConfig
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


class TomographyFineRefinementNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyFineRefinementData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyFineRefinementData

	companion object : NodeClientInfo {

		override val config = TomographyFineRefinementNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-filter")
		override val jobClass = TomographyFineRefinementData::class
		override val urlFragment = "tomographyFineRefinement"

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyFineRefinementNode(viewport, diagram, project, job as TomographyFineRefinementData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyFineRefinementNode?)?.job?.args
				?: JobArgs.fromNext(TomographyFineRefinementArgs(newArgValues(project, input)))
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyFineRefinement.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyFineRefinementNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyFineRefinementArgs(
				values = argValues
			)
			return Services.tomographyFineRefinement.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographyFineRefinementData =
			Services.tomographyFineRefinement.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyFineRefinement.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyFineRefinementArgs>?, enabled: Boolean, onDone: (TomographyFineRefinementArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyFineRefinementArgs> {
				add(TomographyFineRefinementArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
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
			if (job.jobInfoString.split(":").size > 1) {
				div {
					text(job.jobInfoString.split(":")[1])
				}
				div {
					text(job.jobInfoString.split(":")[0])
				}
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
					baseJob = Services.tomographyFineRefinement.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
