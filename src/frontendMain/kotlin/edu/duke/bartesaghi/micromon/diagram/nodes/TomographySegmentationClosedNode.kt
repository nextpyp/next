package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.formatWithDigitGroupsSeparator
import edu.duke.bartesaghi.micromon.nodes.TomographySegmentationClosedNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.filterForDownstreamCopy
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.TomographySegmentationClosedView
import edu.duke.bartesaghi.micromon.views.Viewport
import io.kvision.form.select.SelectRemote
import io.kvision.form.formPanel
import io.kvision.modal.Modal
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType


class TomographySegmentationClosedNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: TomographySegmentationClosedData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as TomographySegmentationClosedData

	companion object : NodeClientInfo {

		override val config = TomographySegmentationClosedNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-draw-polygon")
		override val jobClass = TomographySegmentationClosedData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			TomographySegmentationClosedNode(viewport, diagram, project, job as TomographySegmentationClosedData)

		override suspend fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
			val defaultArgs = (copyFrom as TomographySegmentationClosedNode?)?.job?.args
			form(config.name, outNode, defaultArgs, true) { args ->

				// save the node to the server
				AppScope.launch {
					val data = Services.tomographySegmentationClosed.addNode(project.owner.id, project.projectId, input, args)

					// send the node back to the diagram
					callback(TomographySegmentationClosedNode(viewport, diagram, project, data))
				}
			}
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			@Suppress("NAME_SHADOWING")
			val input = input
				?: throw IllegalArgumentException("input required to make job for ${config.id}")
			val args = TomographySegmentationClosedArgs(
				values = argValues,
				filter = null
			)
			return Services.tomographySegmentationClosed.addNode(project.owner.id, project.projectId, input, args)
		}

		override suspend fun getJob(jobId: String): TomographySegmentationClosedData =
			Services.tomographySegmentationClosed.get(jobId)

		override val pypArgs = ClientPypArgs {
			Services.tomographySegmentationClosed.getArgs(it)
		}

		private fun form(caption: String, upstreamNode: Node, args: JobArgs<TomographySegmentationClosedArgs>?, enabled: Boolean, onDone: (TomographySegmentationClosedArgs) -> Unit) = AppScope.launch {

			val pypArgsWithForwarded = pypArgs.get(true)
			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<TomographySegmentationClosedArgs>().apply {

				val upstreamIsPreprocessing =
					upstreamNode is TomographyPreprocessingNode
					|| upstreamNode is TomographyPurePreprocessingNode
					|| upstreamNode is TomographyImportDataNode
					|| upstreamNode is TomographySessionDataNode
					|| upstreamNode is TomographyRelionDataNode

				add(TomographySegmentationClosedArgs::filter,
					// look for the preprocessing job in the upstream node to get the filter
					if (upstreamIsPreprocessing) {
						SelectRemote(
							serviceManager = BlocksServiceManager,
							function = IBlocksService::filterOptions,
							stateFunction = { upstreamNode.jobId },
							label = "Filter tomograms",
							preload = true
						)
					} else {
						HiddenString()
					}
				)

				add(TomographySegmentationClosedArgs::values, ArgsForm(pypArgs, listOf(upstreamNode), enabled, config.configId))
			}

			// use the none filter option for the particles name in the form,
			// since the control can't handle nulls
			val mapper = ArgsMapper<TomographySegmentationClosedArgs>(
				toForm = { args ->
					if (args.filter == null) {
						args.copy(filter = NoneFilterOption)
					} else {
						args
					}
				},
				fromForm = { args ->
					if (args.filter == NoneFilterOption) {
						args.copy(filter = null)
					} else {
						args
					}
				}
			)
	
			// by default, copy args values from the upstream node
			val argsOrCopy: JobArgs<TomographySegmentationClosedArgs> = args
				?: JobArgs.fromNext(TomographySegmentationClosedArgs(
					filter = null,
					values = upstreamNode.newestArgValues()?.filterForDownstreamCopy(pypArgs) ?: ""
				))

			form.init(argsOrCopy, mapper)
			if (enabled) {
				win.addSaveResetButtons(form, argsOrCopy, mapper) { saving ->
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
				TomographySegmentationClosedView.go(viewport, project, job)
			}) {
				img(job.imageUrl, className = dynamicImageClassName)
			}
			div(className = "count") {
				text("${job.numParticles.formatWithDigitGroupsSeparator()} surfaces")
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
					baseJob = Services.tomographySegmentationClosed.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}
}
