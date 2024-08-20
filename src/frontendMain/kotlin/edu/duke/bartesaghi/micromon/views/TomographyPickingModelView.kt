package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyPickingModelNode
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink


fun Widget.onGoToTomographyPickingModel(viewport: Viewport, project: ProjectData, job: TomographyPickingModelData) {
	onShow(TomographyPickingModelView.path(project, job)) {
		viewport.setView(TomographyPickingModelView(project, job))
	}
}

class TomographyPickingModelView(val project: ProjectData, val job: TomographyPickingModelData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyPickingModel/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyPickingModel.get(jobId)
						viewport.setView(TomographyPickingModelView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyPickingModelData) = "/project/${project.owner.id}/${project.projectId}/tomographyPickingModel/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyPickingModelData) {
			routing.show(path(project, job))
			viewport.setView(TomographyPickingModelView(project, job))
		}
	}

	override val elem = Div(classes = setOf("dock-page", "tomography-picking"))


	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographyPickingModelNode.type.iconClass)
					.onGoToTomographyPickingModel(viewport, project, job)
			}
		}

		AppScope.launch {

			// load all the tilt series
			val loadingElem = elem.loading("Fetching tilt-series ...")
			val args = try {
				delayAtLeast(200) {
					TomographyPickingModelNode.pypArgs.get()
				}
			} catch (t: Throwable) {
				elem.errorMessage(t)
				return@launch
			} finally {
				elem.remove(loadingElem)
			}

			fun emptyMsg(msg: String) {
				elem.div(classes = setOf("empty", "spaced")) {
					content = msg
				}
			}

			val trainMethod = job.args.finished?.values
				?.toArgValues(args)
				?.tomoPartrainMethodOrDefault
				?: run {
					emptyMsg("No models to show")
					return@launch
				}

			when (trainMethod) {
				TomoPartrainMethod.None -> emptyMsg("No training method selected")
				TomoPartrainMethod.Milo -> showMilo()
				else -> emptyMsg("Nothing to show for this training method")
			}
		}
	}

	private fun showMilo() {

		elem.h1("Milo-PYP Particle Picking Model")

		// show the file download
		val fileDownload = FileDownloadBadge(".dat file")
		elem.div {
			add(fileDownload)
		}
		AppScope.launch {
			val fileData = Services.jobs.miloData(job.jobId)
				.unwrap()
				?: return@launch
			fileDownload.show(FileDownloadBadge.Info(
				fileData,
				IJobsService.miloDataPath(job.jobId),
				"${job.jobId}_milo.dat"
			))
		}

		// show the 2D results
		elem.add(SizedPanel("2D Results", Storage.miloResults2dSize).apply {
			val img = image(IJobsService.miloResults2dPath(job.jobId, size), classes = setOf("full-width-image"))
			// set the panel resize handler
			onResize = { newSize: ImageSize ->
				img.src = IJobsService.miloResults2dPath(job.jobId, size)
				Storage.miloResults2dSize = newSize
			}
		})

		// show the 3D results
		elem.add(SizedPanel("3D Results", Storage.miloResults3dSize).apply {
			val img = image(IJobsService.miloResults3dPath(job.jobId, size), classes = setOf("full-width-image"))
			// set the panel resize handler
			onResize = { newSize: ImageSize ->
				img.src = IJobsService.miloResults3dPath(job.jobId, size)
				Storage.miloResults3dSize = newSize
			}
		})
	}
}
