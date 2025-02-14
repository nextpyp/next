package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.TomographyCoarseRefinementNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.revalidateDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.IntegratedRefinementView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.formPanel
import io.kvision.form.select.SelectRemote
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographyCoarseRefinementNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographyCoarseRefinementData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographyCoarseRefinementData

	companion object : NodeClientInfo {

		override val config = TomographyCoarseRefinementNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-dot-circle")
		override val jobClass = TomographyCoarseRefinementData::class
		override val urlFragment = "tomographyCoarseRefinement"

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographyCoarseRefinementNode(viewport, diagram, project, job as TomographyCoarseRefinementData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographyCoarseRefinementNode?)?.job?.args
				?: JobArgs.fromNext(TomographyCoarseRefinementArgs(newArgValues(project, input), null))
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographyCoarseRefinement.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographyCoarseRefinementNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographyCoarseRefinementArgs(
				values = argValues,
				filter = null
			)
			return Services.tomographyCoarseRefinement.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographyCoarseRefinementData =
			Services.tomographyCoarseRefinement.get(jobId)

		override val pypArgs = ServerVal {
			Args.fromJson(Services.tomographyCoarseRefinement.getArgs())
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographyCoarseRefinementArgs>?, enabled: Boolean, onDone: (TomographyCoarseRefinementArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographyCoarseRefinementArgs>().apply {

				val upstreamIsPreprocessing =
					upstreamNode is TomographyPreprocessingNode
					|| upstreamNode is TomographyImportDataNode
					|| upstreamNode is TomographySessionDataNode
					|| upstreamNode is TomographyPurePreprocessingNode
					|| upstreamNode is TomographyDenoisingEvalNode
				add(TomographyCoarseRefinementArgs::filter,
					// look for the preprocessing job in the upstream node to get the filter
					if (upstreamIsPreprocessing) {
						SelectRemote(
							serviceManager = BlocksServiceManager,
							function = IBlocksService::filterOptions,
							stateFunction = { upstreamNode.jobId },
							label = "Filter tilt-series",
							preload = true
						)
					} else {
						HiddenString()
					}
				)

				add(TomographyCoarseRefinementArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// use the none filter option for the particles name in the form,
			// since the control can't handle nulls
			val mapper = ArgsMapper<TomographyCoarseRefinementArgs>(
				toForm = { args ->
					var a = args
					if (a.filter == null) {
						a = a.copy(filter = NoneFilterOption)
					}
					a
				},
				fromForm = { args ->
					var a = args
					if (a.filter == NoneFilterOption) {
						a = a.copy(filter = null)
					}
					a
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
					baseJob = Services.tomographyCoarseRefinement.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
