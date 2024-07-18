package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographySegmentationView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographySegmentationNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographySegmentationData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographySegmentationData

	companion object : NodeClientInfo {

		override val config = TomographySegmentationNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-chart-bar")
		override val jobClass = TomographySegmentationData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographySegmentationNode(viewport, diagram, project, job as TomographySegmentationData)

		override fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographySegmentationNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographySegmentation.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographySegmentationNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographySegmentationArgs(
				values = argValues
			)
			return Services.tomographySegmentation.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographySegmentationData =
			Services.tomographySegmentation.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographySegmentation.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographySegmentationArgs>?, enabled: Boolean, onDone: (TomographySegmentationArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographySegmentationArgs>().apply {
				add(TomographySegmentationArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// by default, copy args values from the upstream node
			val argsOrCopy: JobArgs<TomographySegmentationArgs> = args
				?: JobArgs.fromNext(TomographySegmentationArgs(
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
				TomographySegmentationView.go(viewport, project, job)
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
					baseJob = Services.tomographySegmentation.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
