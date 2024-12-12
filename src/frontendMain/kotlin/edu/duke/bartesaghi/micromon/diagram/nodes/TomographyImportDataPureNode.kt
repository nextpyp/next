package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographyImportDataPureNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyImportDataPureView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyImportDataPureNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyImportDataPureData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyImportDataPureData

	companion object : NodeClientInfo {

		override val config = TomographyImportDataPureNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-cubes")
		override val jobClass = TomographyImportDataPureData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyImportDataPureNode(viewport, diagram, project, job as TomographyImportDataPureData)

		override fun showImportForm(viewport: Viewport, diagram: Diagram, project: ProjectData, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyImportDataPureNode?)?.job?.args
			form(config.name, defaultArgs, true) { args ->
				AppScope.launch {

					// import the data
					val job = Services.tomographyImportDataPure.import(project.owner.id, project.projectId, args)

					callback(TomographyImportDataPureNode(viewport, diagram, project, job))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			val args = TomographyImportDataPureArgs(
				values = argValues
			)
			return Services.tomographyImportDataPure.import(project.owner.id, project.projectId, args)
		}

		override suspend fun getJob(jobId: String): TomographyImportDataPureData =
			Services.tomographyImportDataPure.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyImportDataPure.getArgs())
		}

		private fun form(caption: String, args: JobArgs<TomographyImportDataPureArgs>?, enabled: Boolean, jobId: String? = null, onDone: (TomographyImportDataPureArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyImportDataPureArgs>().apply {
				add(TomographyImportDataPureArgs::values, ArgsForm(pypArgs, emptyList(), enabled, config.configId))
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
				TomographyImportDataPureView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName)
			}
			div(className = "count") {
				div {
					text("${job.numTiltSeries} tilt-series")
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
		form(job.numberedName, job.args, enabled, job.jobId) { newArgs ->

			// save the edits if needed
			val diff = job.args.diff(newArgs)
			if (diff.shouldSave) {
				AppScope.launch {
					baseJob = Services.tomographyImportDataPure.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
