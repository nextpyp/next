package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.nodes.TomographyDenoisingEvalNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyDenoisingEvalView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.select.SelectRemote
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyDenoisingEvalNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyDenoisingEvalData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyDenoisingEvalData

	companion object : NodeClientInfo {

		override val config = TomographyDenoisingEvalNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-hand-sparkles") // TODO: pick an icon
		override val jobClass = TomographyDenoisingEvalData::class
		override val urlFragment = "tomographyDenoisingEval"

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyDenoisingEvalNode(viewport, diagram, project, job as TomographyDenoisingEvalData)

		override fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, andCopyData: Boolean, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyDenoisingEvalNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyDenoisingEval.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyDenoisingEvalNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyDenoisingEvalArgs(
				values = argValues
			)
			return Services.tomographyDenoisingEval.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographyDenoisingEvalData =
			Services.tomographyDenoisingEval.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyDenoisingEval.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyDenoisingEvalArgs>?, enabled: Boolean, onDone: (TomographyDenoisingEvalArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyDenoisingEvalArgs>().apply {
				add(TomographyDenoisingEvalArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// by default, copy args values from the upstream node
			val argsOrCopy: JobArgs<TomographyDenoisingEvalArgs> = args
				?: JobArgs.fromNext(TomographyDenoisingEvalArgs(
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
				// TODO: view?
				TomographyDenoisingEvalView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName)
			}
			div(className = "count") {
				text("${job.numTiltSeries.formatWithDigitGroupsSeparator()} tilt-series")
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
					baseJob = Services.tomographyDenoisingEval.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
