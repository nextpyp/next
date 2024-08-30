package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyMiloEvalNode
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink


fun Widget.onGoToTomographyMiloEval(viewport: Viewport, project: ProjectData, job: TomographyMiloEvalData) {
	onShow(TomographyMiloEvalView.path(project, job)) {
		viewport.setView(TomographyMiloEvalView(project, job))
	}
}

class TomographyMiloEvalView(val project: ProjectData, val job: TomographyMiloEvalData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyMiloEval/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyMiloEval.get(jobId)
						viewport.setView(TomographyMiloEvalView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyMiloEvalData) = "/project/${project.owner.id}/${project.projectId}/tomographyMiloEval/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyMiloEvalData) {
			routing.show(path(project, job))
			viewport.setView(TomographyMiloEvalView(project, job))
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
				navLink(job.numberedName, icon = TomographyMiloEvalNode.type.iconClass)
					.onGoToTomographyMiloEval(viewport, project, job)
			}
		}

		AppScope.launch {

			elem.h1("Milo-PYP Pattern Mining")

			// show the file download
			val fileDownload = FileDownloadBadge(".npz file")
			elem.div {
				add(fileDownload)
			}
			val fileData = Services.tomographyMiloEval.data(job.jobId)
				.unwrap()
				?: return@launch
			fileDownload.show(FileDownloadBadge.Info(
				fileData,
				ITomographyMiloEvalService.dataPath(job.jobId),
				"${job.jobId}_milo.npz"
			))

			// show the 2D results
			elem.add(SizedPanel("2D Results", Storage.miloResults2dSize).apply {
				val img = image(ITomographyMiloEvalService.results2dPath(job.jobId, size), classes = setOf("full-width-image"))
				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyMiloEvalService.results2dPath(job.jobId, size)
					Storage.miloResults2dSize = newSize
				}
			})

			elem.add(SizedPanel("2D Results Labels", Storage.miloResults2dSize).apply {
				val img = image(ITomographyMiloEvalService.results2dLabelsPath(job.jobId, size), classes = setOf("full-width-image"))
				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyMiloEvalService.results2dLabelsPath(job.jobId, size)
					Storage.miloResults2dSize = newSize
				}
			})

			// show the 3D results
			elem.add(SizedPanel("3D Results", Storage.miloResults3dSize).apply {
				val img = image(ITomographyMiloEvalService.results3dPath(job.jobId, size), classes = setOf("full-width-image"))
				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyMiloEvalService.results3dPath(job.jobId, size)
					Storage.miloResults3dSize = newSize
				}
			})
		}
	}
}
