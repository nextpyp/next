package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.errorMessage
import edu.duke.bartesaghi.micromon.nodes.TomographyRelionDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.importRelionPath
import edu.duke.bartesaghi.micromon.pyp.toArgValues
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.select.SelectRemote
import io.kvision.html.image
import io.kvision.modal.Modal
import js.getHTMLElement
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyRelionDataNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyRelionDataData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyRelionDataData

	companion object : NodeClientInfo {

		override val config = TomographyRelionDataNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-cubes")
		override val jobClass = TomographyRelionDataData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyRelionDataNode(viewport, diagram, project, job as TomographyRelionDataData)

		override fun showImportForm(viewport: Viewport, diagram: Diagram, project: ProjectData, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyRelionDataNode?)?.job?.args
			form(config.name, defaultArgs, true) { args ->
				AppScope.launch {

					// import the data
					val job = Services.tomographyRelionData.import(project.owner.id, project.projectId, args)

					callback(TomographyRelionDataNode(viewport, diagram, project, job))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			val args = TomographyRelionDataArgs(
				values = argValues
			)
			return Services.tomographyRelionData.import(project.owner.id, project.projectId, args)
		}

		override suspend fun getJob(jobId: String): TomographyRelionDataData =
			Services.tomographyRelionData.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyRelionData.getArgs())
		}

		private fun form(caption: String, args: JobArgs<TomographyRelionDataArgs>?, enabled: Boolean, jobId: String? = null, onDone: (TomographyRelionDataArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyRelionDataArgs>().apply {
				add(TomographyRelionDataArgs::values, ArgsForm(pypArgs, emptyList(), enabled, config.configId))
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
		AppScope.launch {

			// get the job data, if any
			val (args, display) = job.newestArgsAndDisplay()
				?: return@launch content {
					errorMessage("Job has no data")
				}

			val pypArgs = pypArgs.get()
			val values = args.values.toArgValues(pypArgs)

			content {

				// show an image
				div {
					button(className = "image-button", onClick = {
						popupDetail(refreshImages)
					}) {
						img(job.imageUrl, className = dynamicImageClassName)
					}
				}

				// show some of the args in the UI
				div {
					val path = values.importRelionPath
					if (path != null) {
						div(className = "ellipsis") {
							text(path)
						}
						div(className = "count") {
							text("${display.numTiltSeries?.formatWithDigitGroupsSeparator() ?: "??"} file(s)")
						}
					} else {
						span(className = "empty") {
							text("(no files chosen)")
						}
					}
				}
			}

			if (refreshImages) {
				getHTMLElement()?.refreshDynamicImages()
			}
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
					baseJob = Services.tomographyRelionData.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values

	private fun popupDetail(refreshImages: Boolean) {

		val win = Modal(
			caption = Companion.config.name,
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup", "large-image-popup")
		)
		win.image("/kv/jobs/$jobId/gainCorrectedImage/${ImageSize.Large.id}", classes = setOf(dynamicImageClassName, "large-scale-down"))
		win.show()

		if (refreshImages) {
			win.getHTMLElement()?.refreshDynamicImages()
		}
	}
}
