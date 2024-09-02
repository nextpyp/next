package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyMiloTrainNode
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink


fun Widget.onGoToTomographyMiloTrain(viewport: Viewport, project: ProjectData, job: TomographyMiloTrainData) {
	onShow(TomographyMiloTrainView.path(project, job)) {
		viewport.setView(TomographyMiloTrainView(project, job))
	}
}

class TomographyMiloTrainView(val project: ProjectData, val job: TomographyMiloTrainData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyMiloTrain/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyMiloTrain.get(jobId)
						viewport.setView(TomographyMiloTrainView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyMiloTrainData) = "/project/${project.owner.id}/${project.projectId}/tomographyMiloTrain/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyMiloTrainData) {
			routing.show(path(project, job))
			viewport.setView(TomographyMiloTrainView(project, job))
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
				navLink(job.numberedName, icon = TomographyMiloTrainNode.type.iconClass)
					.onGoToTomographyMiloTrain(viewport, project, job)
			}
		}

		AppScope.launch {

			elem.h1("MiLoPYP Training Results")

			// show the 2D results
			elem.add(SizedPanel("Loss Function", Storage.miloResults2dSize).apply {
				val img = image(ITomographyMiloTrainService.resultsPath(job.jobId), classes = setOf("full-width-image"))
				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyMiloTrainService.resultsPath(job.jobId)
					Storage.miloResults2dSize = newSize
				}
			})
		}
	}
}
