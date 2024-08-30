package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.SingleParticlePreprocessingNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.SingleParticlePreprocessingView
import edu.duke.bartesaghi.micromon.views.Viewport
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType
import io.kvision.form.formPanel
import io.kvision.form.select.SelectRemote
import io.kvision.modal.Modal


class SingleParticlePreprocessingNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: SingleParticlePreprocessingData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as SingleParticlePreprocessingData

	companion object : NodeClientInfo {

		override val config = SingleParticlePreprocessingNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "far fa-chart-bar")
		override val jobClass = SingleParticlePreprocessingData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			SingleParticlePreprocessingNode(viewport, diagram, project, job as SingleParticlePreprocessingData)

		override fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, andCopyData: Boolean, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as SingleParticlePreprocessingNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.singleParticlePreprocessing.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(SingleParticlePreprocessingNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = SingleParticlePreprocessingArgs(
				values = argValues,
				particlesName = null
			)
			return Services.singleParticlePreprocessing.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): SingleParticlePreprocessingData =
			Services.singleParticlePreprocessing.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.singleParticlePreprocessing.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<SingleParticlePreprocessingArgs>?, enabled: Boolean, jobId: String?=null, onDone: (SingleParticlePreprocessingArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<SingleParticlePreprocessingArgs>().apply {

				add(SingleParticlePreprocessingArgs::particlesName,
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

				add(SingleParticlePreprocessingArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// use the none filter option for the particles name in the form,
			// since the control can't handle nulls
			val mapper = ArgsMapper<SingleParticlePreprocessingArgs>(
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

			// by default, copy args values from the upstream node
			val argsOrCopy: JobArgs<SingleParticlePreprocessingArgs> = args
				?: JobArgs.fromNext(SingleParticlePreprocessingArgs(
					particlesName = null,
					values = upstreamNode.newestArgValues()?.filterForDownstreamCopy(pypArgs) ?: ""
				))

			form.init(argsOrCopy, mapper)
			if (enabled) {
				win.addSaveResetButtons(form, argsOrCopy, mapper, onDone)
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
				SingleParticlePreprocessingView.go(viewport, project, job)
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
		form(job.numberedName, getUpstreamNodeOrThrow(), job.args, enabled, job.jobId) { newArgs ->

			// save the edits if needed
			val diff = job.args.diff(newArgs)
			if (diff.shouldSave) {
				AppScope.launch {
					baseJob = Services.singleParticlePreprocessing.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
