package edu.duke.bartesaghi.micromon.views

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.*
import edu.duke.bartesaghi.micromon.diagram.nodes.TomographyParticlesTrainNode
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Widget
import io.kvision.html.*
import io.kvision.navbar.navLink


fun Widget.onGoToTomographyParticlesTrain(viewport: Viewport, project: ProjectData, job: TomographyParticlesTrainData) {
	onShow(TomographyParticlesTrainView.path(project, job)) {
		viewport.setView(TomographyParticlesTrainView(project, job))
	}
}

class TomographyParticlesTrainView(val project: ProjectData, val job: TomographyParticlesTrainData) : View {

	companion object : Routed {

		override fun register(routing: Routing, viewport: Viewport) {
			routing.registerParams("^/project/($urlToken)/($urlToken)/tomographyParticlesTrain/($urlToken)$") { userId, projectId, jobId ->
				AppScope.launch {
					try {
						val project = Services.projects.get(userId, projectId)
						val job = Services.tomographyParticlesTrain.get(jobId)
						viewport.setView(TomographyParticlesTrainView(project, job))
					} catch (t: Throwable) {
						viewport.setView(ErrorView(t))
					}
				}
			}
		}

		fun path(project: ProjectData, job: TomographyParticlesTrainData) = "/project/${project.owner.id}/${project.projectId}/tomographyParticlesTrain/${job.jobId}"

		fun go(viewport: Viewport, project: ProjectData, job: TomographyParticlesTrainData) {
			routing.show(path(project, job))
			viewport.setView(TomographyParticlesTrainView(project, job))
		}
	}

	override val routed = Companion
	override val elem = Div(classes = setOf("dock-page", "tomography-picking"))


	override fun init(viewport: Viewport) {

		// update the navbar
		viewport.navbarElem.apply {
			navex {
				navLink("Dashboard", icon = "fas fa-tachometer-alt")
					.onGoToDashboard()
				navLink(project.numberedName, icon = "fas fa-project-diagram")
					.onGoToProject(project)
				navLink(job.numberedName, icon = TomographyParticlesTrainNode.type.iconClass)
					.onGoToTomographyParticlesTrain(viewport, project, job)
			}
		}

		AppScope.launch {

			elem.h1("Training Results")

			// show the 2D results
			elem.add(SizedPanel("Loss Functions", Storage.particlesResults2dSize).apply {
				val img = image(ITomographyParticlesTrainService.resultsPath(job.jobId), classes = setOf("full-width-image"))
				img?.refresh()
				// set the panel resize handler
				onResize = { newSize: ImageSize ->
					img.src = ITomographyParticlesTrainService.resultsPath(job.jobId)
					Storage.particlesResults2dSize = newSize
				}
			})
		}
	}
}
