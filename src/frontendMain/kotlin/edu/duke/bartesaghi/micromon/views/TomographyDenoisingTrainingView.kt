package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyDenoisingTrainingNode
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink


fun Widget.onGoToTomographyDenoisingTraining(viewport: Viewport, project: ProjectData, job: TomographyDenoisingTrainingData) {
	onShow(TomographyDenoisingTrainingView.path(project, job)) {
		viewport.setView(TomographyDenoisingTrainingView(project, job))
	}
}

class TomographyDenoisingTrainingView(val project: ProjectData, val job: TomographyDenoisingTrainingData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyDenoisingTraining/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyDenoisingTraining.get(jobId)
						viewport.setView(TomographyDenoisingTrainingView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyDenoisingTrainingData) = "/project/${project.owner.id}/${project.projectId}/tomographyDenoisingTraining/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyDenoisingTrainingData) {
			routing.show(path(project, job))
			viewport.setView(TomographyDenoisingTrainingView(project, job))
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
				navLink(job.numberedName, icon = TomographyDenoisingTrainingNode.type.iconClass)
					.onGoToTomographyDenoisingTraining(viewport, project, job)
			}
		}

		AppScope.launch {

			elem.h1("Denoising Training Results")

			// show the results
			elem.add(SizedPanel("Loss Functions", Storage.tomographyDenoisingTrainingResultsSize).apply {
				val img = image(ITomographyDenoisingTrainingService.trainResultsPath(job.jobId), classes = setOf("full-width-image"))
				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyDenoisingTrainingService.trainResultsPath(job.jobId)
					Storage.tomographyDenoisingTrainingResultsSize = newSize
				}
			})
		}
	}
}
