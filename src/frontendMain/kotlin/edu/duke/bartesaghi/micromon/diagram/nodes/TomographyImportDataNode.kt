package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.nodes.TomographyImportDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyImportDataView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.select.SelectRemote
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyImportDataNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyImportDataData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyImportDataData

	companion object : NodeClientInfo {

		override val config = TomographyImportDataNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-cubes")
		override val jobClass = TomographyImportDataData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyImportDataNode(viewport, diagram, project, job as TomographyImportDataData)

		override fun showImportForm(viewport: Viewport, diagram: Diagram, project: ProjectData, copyFrom: Node?, andCopyData: Boolean, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyImportDataNode?)?.job?.args
			form(config.name, defaultArgs, true) { args ->
				AppScope.launch {

					// import the data
					val job = Services.tomographyImportData.import(project.owner.id, project.projectId, args)

					callback(TomographyImportDataNode(viewport, diagram, project, job))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			val args = TomographyImportDataArgs(
				values = argValues,
				particlesName = null
			)
			return Services.tomographyImportData.import(project.owner.id, project.projectId, args)
		}

		override suspend fun getJob(jobId: String): TomographyImportDataData =
			Services.tomographyImportData.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyImportData.getArgs())
		}

		private fun form(caption: String, args: JobArgs<TomographyImportDataArgs>?, enabled: Boolean, jobId: String? = null, onDone: (TomographyImportDataArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyImportDataArgs>().apply {

				add(TomographyImportDataArgs::particlesName,
					if (jobId != null) {
						SelectRemote(
							serviceManager = ParticlesServiceManager,
							function = IParticlesService::getListOptions,
							stateFunction = { "${OwnerType.Project.id}/$jobId" },
							label = "Select list of positions",
							preload = true
						)
					} else {
						HiddenString()
					}
				)

				add(TomographyImportDataArgs::values, ArgsForm(pypArgs, emptyList(), enabled, config.configId))
			}

			// use the none filter option for the particles name in the form,
			// since the control can't handle nulls
			val mapper = ArgsMapper<TomographyImportDataArgs>(
				toForm = { args ->
					if (args.particlesName == null) {
						args.copy(particlesName = NoneFilterOption)
					} else {
						args
					}
				},
				fromForm = { args ->
					if (args.particlesName == NoneFilterOption) {
						args.copy(particlesName = null)
					} else {
						args
					}
				}
			)

			form.init(args, mapper)
			if (enabled) {
				win.addSaveResetButtons(form, args, mapper, onDone)
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
				TomographyImportDataView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName)
			}
			div(className = "count") {
				div {
					text("${job.numTiltSeries} tilt-series")
				}
				div {
					text("${job.numParticles.formatWithDigitGroupsSeparator()} particle(s)")
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
					baseJob = Services.tomographyImportData.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
