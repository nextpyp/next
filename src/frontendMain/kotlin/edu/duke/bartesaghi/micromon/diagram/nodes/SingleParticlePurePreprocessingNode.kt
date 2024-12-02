package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePurePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.Viewport
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType
import io.kvision.form.formPanel
import io.kvision.modal.Modal


class SingleParticlePurePreprocessingNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: SingleParticlePurePreprocessingData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as SingleParticlePurePreprocessingData

	companion object : NodeClientInfo {

		override val config = SingleParticlePurePreprocessingNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "far fa-chart-bar")
		override val jobClass = SingleParticlePurePreprocessingData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			SingleParticlePurePreprocessingNode(viewport, diagram, project, job as SingleParticlePurePreprocessingData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as SingleParticlePurePreprocessingNode?)?.job?.args
				?: JobArgs.fromNext(SingleParticlePurePreprocessingArgs(newArgValues(project, input)))
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.singleParticlePurePreprocessing.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(SingleParticlePurePreprocessingNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = SingleParticlePurePreprocessingArgs(
				values = argValues
			)
			return Services.singleParticlePurePreprocessing.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): SingleParticlePurePreprocessingData =
			Services.singleParticlePurePreprocessing.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.singleParticlePurePreprocessing.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<SingleParticlePurePreprocessingArgs>?, enabled: Boolean, onDone: (SingleParticlePurePreprocessingArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<SingleParticlePurePreprocessingArgs>().apply {
				add(SingleParticlePurePreprocessingArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
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
				//SingleParticlePurePreprocessingView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName)
			}
			div(className = "count") {
				div {
					text("${job.numMicrographs.formatWithDigitGroupsSeparator()} micrograph(s)")
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
					baseJob = Services.singleParticlePurePreprocessing.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
