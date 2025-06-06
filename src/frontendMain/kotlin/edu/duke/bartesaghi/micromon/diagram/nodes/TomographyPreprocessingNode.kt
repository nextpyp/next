package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographyPreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.revalidateDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyPreprocessingView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.form.select.SelectRemote
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyPreprocessingNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyPreprocessingData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyPreprocessingData

	companion object : NodeClientInfo {

		override val config = TomographyPreprocessingNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-chart-bar")
		override val jobClass = TomographyPreprocessingData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyPreprocessingNode(viewport, diagram, project, job as TomographyPreprocessingData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyPreprocessingNode?)?.job?.args
				?: JobArgs.fromNext(TomographyPreprocessingArgs(newArgValues(project, input), null))
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyPreprocessing.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyPreprocessingNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyPreprocessingArgs(
				values = argValues,
				tomolist = null
			)
			return Services.tomographyPreprocessing.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographyPreprocessingData =
			Services.tomographyPreprocessing.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyPreprocessing.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyPreprocessingArgs>?, enabled: Boolean, jobId: String? = null, onDone: (TomographyPreprocessingArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyPreprocessingArgs>().apply {

				add(TomographyPreprocessingArgs::tomolist,
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

				add(TomographyPreprocessingArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// use the none filter option for the particles name in the form,
			// since the control can't handle nulls
			val mapper = ArgsMapper<TomographyPreprocessingArgs>(
				toForm = { args ->
					if (args.tomolist == null) {
						args.copy(tomolist = NoneFilterOption)
					} else {
						args
					}
				},
				fromForm = { args ->
					if (args.tomolist == NoneFilterOption) {
						args.copy(tomolist = null)
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
				TomographyPreprocessingView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName)
			}
			div(className = "count") {
				div {
					text("${job.numTiltSeries.formatWithDigitGroupsSeparator()} tilt-series")
				}
				div {
					text("${job.numParticles.formatWithDigitGroupsSeparator()} particle(s)")
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
		form(job.numberedName, getUpstreamNodeOrThrow(), job.args, enabled, job.jobId) { newArgs ->

			// save the edits if needed
			val diff = job.args.diff(newArgs)
			if (diff.shouldSave) {
				AppScope.launch {
					baseJob = Services.tomographyPreprocessing.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
