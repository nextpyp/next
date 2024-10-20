package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.SingleParticleImportDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.SingleParticleImportDataView
import edu.duke.bartesaghi.micromon.views.Viewport
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType
import io.kvision.form.formPanel
import io.kvision.modal.Modal


class SingleParticleImportDataNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: SingleParticleImportDataData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as SingleParticleImportDataData

	companion object : NodeClientInfo {

		override val config = SingleParticleImportDataNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-layer-group")
		override val jobClass = SingleParticleImportDataData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			SingleParticleImportDataNode(viewport, diagram, project, job as SingleParticleImportDataData)

		override fun showImportForm(viewport: Viewport, diagram: Diagram, project: ProjectData, copyFrom: Node?, callback: (Node) -> Unit) {

			val defaultArgs = (copyFrom as SingleParticleImportDataNode?)?.job?.args
			form(config.name, defaultArgs, true) { args ->
				AppScope.launch {

					// import the data
					val job = Services.singleParticleImportData.import(project.owner.id, project.projectId, args)

					callback(SingleParticleImportDataNode(viewport, diagram, project, job))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			val args = SingleParticleImportDataArgs(
				values = argValues
			)
			return Services.singleParticleImportData.import(project.owner.id, project.projectId, args)
		}

		override suspend fun getJob(jobId: String): SingleParticleImportDataData =
			Services.singleParticleImportData.get(jobId)


		override val pypArgs = ClientPypArgs {
			Services.singleParticleImportData.getArgs(it)
		}

		private fun form(caption: String, args: JobArgs<SingleParticleImportDataArgs>?, enabled: Boolean, jobId: String? = null, onDone: (SingleParticleImportDataArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<SingleParticleImportDataArgs>().apply {
				add(SingleParticleImportDataArgs::values, ArgsForm(pypArgs, emptyList(), enabled, config.configId))
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
				SingleParticleImportDataView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName)
			}
			div(className = "count") {
				div {
					text("${job.numMicrographs.formatWithDigitGroupsSeparator()} micrograph(s)")
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
					baseJob = Services.singleParticleImportData.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
