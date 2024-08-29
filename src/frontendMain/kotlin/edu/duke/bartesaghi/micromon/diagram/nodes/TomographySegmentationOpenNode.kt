package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationOpenNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographySegmentationOpenView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographySegmentationOpenNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographySegmentationOpenData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographySegmentationOpenData

	companion object : NodeClientInfo {

		override val config = TomographySegmentationOpenNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-crosshairs")
		override val jobClass = TomographySegmentationOpenData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographySegmentationOpenNode(viewport, diagram, project, job as TomographySegmentationOpenData)

		override fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographySegmentationOpenNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographySegmentationOpen.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographySegmentationOpenNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographySegmentationOpenArgs(
				values = argValues
			)
			return Services.tomographySegmentationOpen.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographySegmentationOpenData =
			Services.tomographySegmentationOpen.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographySegmentationOpen.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographySegmentationOpenArgs>?, enabled: Boolean, onDone: (TomographySegmentationOpenArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographySegmentationOpenArgs>().apply {
				add(TomographySegmentationOpenArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// by default, copy args values from the upstream node
			val argsOrCopy: JobArgs<TomographySegmentationOpenArgs> = args
				?: JobArgs.fromNext(TomographySegmentationOpenArgs(
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
				TomographySegmentationOpenView.go(viewport, project, job)
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
					baseJob = Services.tomographySegmentationOpen.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
