package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePickingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.Viewport
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType
import io.kvision.form.formPanel
import io.kvision.modal.Modal


class SingleParticlePickingNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: SingleParticlePickingData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as SingleParticlePickingData

	companion object : NodeClientInfo {

		override val config = SingleParticlePickingNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-crosshairs")
		override val jobClass = SingleParticlePickingData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			SingleParticlePickingNode(viewport, diagram, project, job as SingleParticlePickingData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as SingleParticlePickingNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.singleParticlePicking.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(SingleParticlePickingNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = SingleParticlePickingArgs(
				values = argValues
			)
			return Services.singleParticlePicking.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): SingleParticlePickingData =
			Services.singleParticlePicking.get(jobId)

		override val pypArgs = ClientPypArgs {
			Services.singleParticlePicking.getArgs(it)
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<SingleParticlePickingArgs>?, enabled: Boolean, onDone: (SingleParticlePickingArgs) -> Unit) = AppScope.launch {

			val pypArgsWithForwarded = pypArgs.get(true)
			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<SingleParticlePickingArgs>().apply {
				add(SingleParticlePickingArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// by default, copy args values from the upstream node
			val argsOrCopy: JobArgs<SingleParticlePickingArgs> = args
				?: JobArgs.fromNext(SingleParticlePickingArgs(
					values = upstreamNode.newestArgValues()?.filterForDownstreamCopy(pypArgs) ?: ""
				))

			form.init(argsOrCopy)
			if (enabled) {
				win.addSaveResetButtons(form, argsOrCopy) { saving ->
					val merged = Nodes.mergeForwardedArgsIfNeeded(pypArgs, pypArgsWithForwarded, saving.values, upstreamNode)
					onDone(merged?.let { saving.copy(values = it) } ?: saving)
				}
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
				//SingleParticlePickingView.go(viewport, project, job)
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
					baseJob = Services.singleParticlePicking.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
