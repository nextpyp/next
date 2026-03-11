package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.SingleParticleCoarseRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.revalidateDynamicImages
import edu.duke.bartesaghi.micromon.services.PreprocessingFilterType
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.IntegratedRefinementView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.form.select.SelectRemote
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class SingleParticleCoarseRefinementNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: SingleParticleCoarseRefinementData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as SingleParticleCoarseRefinementData

	companion object : NodeClientInfo {

		override val config = SingleParticleCoarseRefinementNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-dot-circle")
		override val jobClass = SingleParticleCoarseRefinementData::class
		override val urlFragment = "singleParticleCoarseRefinement"

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			SingleParticleCoarseRefinementNode(viewport, diagram, project, job as SingleParticleCoarseRefinementData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as SingleParticleCoarseRefinementNode?)?.job?.args
				?: JobArgs.fromNext(SingleParticleCoarseRefinementArgs(newArgValues(project, input), null, null))
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.singleParticleCoarseRefinement.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(SingleParticleCoarseRefinementNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = SingleParticleCoarseRefinementArgs(
				values = argValues,
				filterTable = null,
				filterPrismpyp = null
			)
			return Services.singleParticleCoarseRefinement.addNode(project.owner.id, project.projectId, input, args)
		}

		override val pypArgs = ServerVal {
			Args.fromJson(Services.singleParticleCoarseRefinement.getArgs())
		}

		override suspend fun getJob(jobId: String): SingleParticleCoarseRefinementData =
			Services.singleParticleCoarseRefinement.get(jobId)

		fun form(caption: String, upstreamNode: Node, args: JobArgs<SingleParticleCoarseRefinementArgs>?, enabled: Boolean, onDone: (SingleParticleCoarseRefinementArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<SingleParticleCoarseRefinementArgs>().apply {

				val upstreamIsPreprocessing =
					upstreamNode is SingleParticlePreprocessingNode
					|| upstreamNode is SingleParticleImportDataNode
					|| upstreamNode is SingleParticleSessionDataNode

				if (upstreamIsPreprocessing) {
					add(SingleParticleCoarseRefinementArgs::filterTable,
						SelectRemote(
							serviceManager = BlocksServiceManager,
							function = IBlocksService::filterOptions,
							stateFunction = { "${upstreamNode.jobId},${PreprocessingFilterType.TABLE.name}" },
							label = "Filter micrographs (Table)",
							preload = true
						)
					)
					add(SingleParticleCoarseRefinementArgs::filterPrismpyp,
						SelectRemote(
							serviceManager = BlocksServiceManager,
							function = IBlocksService::filterOptions,
							stateFunction = { "${upstreamNode.jobId},${PreprocessingFilterType.PRISMPYP.name}" },
							label = "Filter micrographs (prismPYP)",
							preload = true
						)
					)
				} else {
					add(SingleParticleCoarseRefinementArgs::filterTable, HiddenString())
					add(SingleParticleCoarseRefinementArgs::filterPrismpyp, HiddenString())
				}

				add(SingleParticleCoarseRefinementArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// use the none filter option for the dropdowns, since the control can't handle nulls
			val mapper = ArgsMapper<SingleParticleCoarseRefinementArgs>(
				toForm = { a ->
					var out = a
					if (a.filterTable == null) out = out.copy(filterTable = NoneFilterOption)
					if (a.filterPrismpyp == null) out = out.copy(filterPrismpyp = NoneFilterOption)
					out
				},
				fromForm = { a ->
					var out = a
					if (a.filterTable == NoneFilterOption) out = out.copy(filterTable = null)
					if (a.filterPrismpyp == NoneFilterOption) out = out.copy(filterPrismpyp = null)
					out
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
			button(className = "image-button reconstruction-node", onClick = {
				IntegratedRefinementView.go(viewport, project, job)
			}) {
				img(job.imageURL, className = dynamicImageClassName)
			}
			if (job.jobInfoString.split(":").size > 1) {
				div {
					text(job.jobInfoString.split(":")[1])
				}
				div {
					text(job.jobInfoString.split(":")[0])
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
		form(job.numberedName, getUpstreamNodeOrThrow(), job.args, enabled) { newArgs ->

			// save the edits if needed
			val diff = job.args.diff(newArgs)
			if (diff.shouldSave) {
				AppScope.launch {
					baseJob = Services.singleParticleCoarseRefinement.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
