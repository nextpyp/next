package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.ArgsForm
import edu.duke.bartesaghi.micromon.components.forms.addSaveResetButtons
import edu.duke.bartesaghi.micromon.components.forms.init
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographyFlexibleRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.IntegratedRefinementView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyFlexibleRefinementNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyFlexibleRefinementData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyFlexibleRefinementData

	companion object : NodeClientInfo {

		override val config = TomographyFlexibleRefinementNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-film")
		override val jobClass = TomographyFlexibleRefinementData::class
		override val urlFragment = "tomographyFlexibleRefinement"

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyFlexibleRefinementNode(viewport, diagram, project, job as TomographyFlexibleRefinementData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyFlexibleRefinementNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyFlexibleRefinement.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyFlexibleRefinementNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyFlexibleRefinementArgs(
				values = argValues
			)
			return Services.tomographyFlexibleRefinement.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographyFlexibleRefinementData =
			Services.tomographyFlexibleRefinement.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyFlexibleRefinement.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyFlexibleRefinementArgs>?, enabled: Boolean, onDone: (TomographyFlexibleRefinementArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyFlexibleRefinementArgs> {
				add(TomographyFlexibleRefinementArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// by default, copy args values from the upstream node
			val argsOrCopy: JobArgs<TomographyFlexibleRefinementArgs> = args
				?: JobArgs.fromNext(TomographyFlexibleRefinementArgs(
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
					baseJob = Services.tomographyFlexibleRefinement.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
