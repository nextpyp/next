package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.*
import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.dynamicImageClassName
import edu.duke.bartesaghi.micromon.nodes.SingleParticleSessionDataNodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.refreshDynamicImages
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.views.SingleParticleSessionDataView
import edu.duke.bartesaghi.micromon.views.Viewport
import js.micromondiagrams.MicromonDiagrams
import js.micromondiagrams.nodeType
import io.kvision.form.FormType
import io.kvision.form.select.SelectRemote
import io.kvision.modal.Modal


class SingleParticleSessionDataNode(
	viewport: Viewport,
	diagram: Diagram,
	project: ProjectData,
	job: SingleParticleSessionDataData
) : Node(viewport, diagram, type, config, project, job) {

	val job get() = baseJob as SingleParticleSessionDataData

	companion object : NodeClientInfo {

		override val config = SingleParticleSessionDataNodeConfig
		override val type = MicromonDiagrams.nodeType(config, "fas fa-layer-group")
		override val jobClass = SingleParticleSessionDataData::class
		override val urlFragment = null

		override fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData) =
			SingleParticleSessionDataNode(viewport, diagram, project, job as SingleParticleSessionDataData)

		override fun showImportForm(viewport: Viewport, diagram: Diagram, project: ProjectData, copyFrom: Node?, callback: (Node) -> Unit) {

			val win = Modal(
				caption = config.name,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<SingleParticleSessionDataArgs>(type = FormType.HORIZONTAL) {
				AppScope.launch {

					add(SingleParticleSessionDataArgs::sessionId, SelectRemote(
						serviceManager = SessionsServiceManager,
						function = ISessionsService::sessionOptions,
						stateFunction = { SingleParticleSessionData.ID },
						label = "Session"
					))

					add(SingleParticleSessionDataArgs::values, HiddenString(""))
				}
			}

			win.addSaveResetButtons(form, null) { args ->
				AppScope.launch {
					val job = Services.singleParticleSessionData.import(project.owner.id, project.projectId, args.sessionId)
					callback(SingleParticleSessionDataNode(viewport, diagram, project, job))
				}
			}
			win.show()
		}

		override suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData {
			throw Error("workflows unsupported for Session imports, need a mechanism to ask for the session id")
			//Services.singleParticleSessionData.import(project.owner.id, project.projectId, sessionId)
			// TODO: would also have to apply the given argValues, which means changing the service call too?
		}

		override suspend fun getJob(jobId: String): SingleParticleSessionDataData =
			Services.singleParticleSessionData.get(jobId)

		override val pypArgs = ClientPypArgs {
			Services.singleParticleSessionData.getArgs(it)
		}


		private fun form(caption: String, args: JobArgs<SingleParticleSessionDataArgs>?, enabled: Boolean, onDone: (SingleParticleSessionDataArgs) -> Unit) = AppScope.launch {

			val pypArgs = pypArgs.get()

			val win = Modal(
				caption = caption,
				escape = true,
				closeButton = true,
				classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
			)

			val form = win.formPanel<SingleParticleSessionDataArgs>().apply {

				add(SingleParticleSessionDataArgs::sessionId, SelectRemote(
					serviceManager = SessionsServiceManager,
					function = ISessionsService::sessionOptions,
					stateFunction = { SingleParticleSessionData.ID },
					label = "Session"
				))

				val sessionId = args?.newest()?.args?.sessionId
				add(SingleParticleSessionDataArgs::particlesName,
					if (sessionId != null) {
						SelectRemote(
							serviceManager = ParticlesServiceManager,
							function = IParticlesService::getListOptions,
							stateFunction = { "${OwnerType.Session.id}/$sessionId" },
							label = "Select list of positions",
							preload = true
						)
					} else {
						HiddenString()
					}
				)

				add(SingleParticleSessionDataArgs::values, ArgsForm(pypArgs, emptyList(), enabled, config.configId))
			}

			// use the none filter option for the particles name in the form,
			// since the control can't handle nulls
			val mapper = ArgsMapper<SingleParticleSessionDataArgs>(
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
				SingleParticleSessionDataView.go(viewport, project, job)
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
		form(job.numberedName, job.args, enabled) { newArgs ->

			// save the edits if needed
			val diff = job.args.diff(newArgs)
			if (diff.shouldSave) {
				AppScope.launch {
					baseJob = Services.singleParticleSessionData.edit(baseJob.jobId, diff.newNextArgs(newArgs))
					edited()
				}
			}
		}
	}

	override fun newestArgValues() =
		job.args.newest()?.args?.values
}
