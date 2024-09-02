package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesTrainNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographyParticlesTrainView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyParticlesTrainNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyParticlesTrainData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyParticlesTrainData

	companion object : NodeClientInfo {

		override val config = TomographyParticlesTrainNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-crosshairs")
		override val jobClass = TomographyParticlesTrainData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyParticlesTrainNode(viewport, diagram, project, job as TomographyParticlesTrainData)

		override fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, andCopyData: Boolean, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyParticlesTrainNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyParticlesTrain.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyParticlesTrainNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyParticlesTrainArgs(
				values = argValues
			)
			return Services.tomographyParticlesTrain.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographyParticlesTrainData =
			Services.tomographyParticlesTrain.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyParticlesTrain.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyParticlesTrainArgs>?, enabled: Boolean, onDone: (TomographyParticlesTrainArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyParticlesTrainArgs>().apply {
				add(TomographyParticlesTrainArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// by default, copy args values from the upstream node
			val argsOrCopy: JobArgs<TomographyParticlesTrainArgs> = args
				?: JobArgs.fromNext(TomographyParticlesTrainArgs(
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
				// TODO: view
				TomographyParticlesTrainView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = "$dynamicImageClassName thumbnail")
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
					baseJob = Services.tomographyParticlesTrain.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
