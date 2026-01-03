package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.addBefore
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesData
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesParticlesData
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.toFixed
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.toast.Toast
import io.kvision.toast.ToastOptions
import io.kvision.toast.ToastPosition
import kotlin.math.abs


/*
 * WARNING:
 *
 * SessionTomoMultiPanel is a copy of this code that does mostly the same thing,
 * but has some subtle differences.
 *
 * If you're in here fixing things, also check to see if you need to fix the same thing in SessionTomoMultiPanel!!
 *
 * TODO: maybe someday we can merge TomoDualPanel and SessionTomoDualPanel together?
 */

class TomoDualPanel(
	val project: ProjectData,
	val job: JobData,
	val tiltSerieses: TiltSeriesesData,
	val tiltSeries: TiltSeriesData,
	val showDenoised: Boolean = false,
	val showSegmented: Boolean = false,
	val showScores: Boolean = false,
	val showScoreMetrics: Boolean = true,
	val showHeatMap: Boolean = false,
	val hideSideViews: Boolean = true,
	val hideDownloadBadges: Boolean = false,
	val pickingControls: SingleListParticleControls? = null
): Div(classes = setOf("tomo-multipanel")) {

	val particlesImage = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, pickingControls)

	val segmentedTomogramImage = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, spriteUrl = "/kv/jobs/${job.jobId}/data/${tiltSeries.id}/segmentedTomogramMontage")

	val denoisedTomogramImage = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, spriteUrl = "/kv/jobs/${job.jobId}/data/${tiltSeries.id}/denoisedTomogramMontage")

	val scoresTomogramImage = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, pickingControls, panelName="Correlation Scores", spriteUrl = "/kv/jobs/${job.jobId}/data/${tiltSeries.id}/reconstructionScoreMontage")

	val heatMapTomogramImage = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, panelName="Heat Map", spriteUrl = "/kv/jobs/${job.jobId}/data/${tiltSeries.id}/predOutTiltSeriesMontage")

	private val recDownloadBadge = FileDownloadBadge(
		filetype = ".rec file",
		url = "kv/jobs/${job.jobId}/data/${tiltSeries.id}/rec",
		filename = "${job.jobId}_${tiltSeries.id}.rec",
		loader = { Services.jobs.recData(job.jobId, tiltSeries.id) }
	)

	private val segmentedDownloadBadge = FileDownloadBadge(
		filetype = "_seg.rec file",
		url = "kv/jobs/${job.jobId}/data/${tiltSeries.id}/seg",
		filename = "${job.jobId}_${tiltSeries.id}_seg.rec",
		loader = { Services.jobs.segData(job.jobId, tiltSeries.id) }
	)

	private val denoisedDownloadBadge = FileDownloadBadge(
		filetype = "_den.rec file",
		url = "kv/jobs/${job.jobId}/data/${tiltSeries.id}/den",
		filename = "${job.jobId}_${tiltSeries.id}_den.rec",
		loader = { Services.jobs.denData(job.jobId, tiltSeries.id) }
	)

	private val scoresDownloadBadge = FileDownloadBadge(
		filetype = "_scores.mrc file",
		url = "kv/jobs/${job.jobId}/data/${tiltSeries.id}/scr",
		filename = "${job.jobId}_${tiltSeries.id}_scores.mrc",
		loader = { Services.jobs.scrData(job.jobId, tiltSeries.id) }
	)

	val tabs = flatLazyTabPanel {

		// DSLs make it super annoying to get to the outer scope
		val self = this@TomoDualPanel

		persistence = Storage::tomographyPreprocessingMultiPanelTabIndex

		addTab("Original") { lazyTab ->
			if ( self.hideDownloadBadges == false ){
				lazyTab.elem.add(self.recDownloadBadge)
			}
			lazyTab.elem.add(self.particlesImage)
			if ( self.hideSideViews == false ){
				lazyTab.elem.add(TomoSideViewImage(self.job.jobId, self.tiltSeries.id))
			}
		}

		if ( self.showSegmented == true ) {
			addTab("Segmentation") { lazyTab ->
				if ( self.hideDownloadBadges == false ){
					lazyTab.elem.add(self.segmentedDownloadBadge)
				}
				lazyTab.elem.add(self.segmentedTomogramImage)
				if ( self.hideSideViews == false ){
					lazyTab.elem.add(TomoSideViewSegImage(self.job.jobId, self.tiltSeries.id))
				}
			}
		}

		if ( self.showDenoised == true ) {
			addTab("Denoised") { lazyTab ->
				if ( self.hideDownloadBadges == false ){
					lazyTab.elem.add(self.denoisedDownloadBadge)
				}
				lazyTab.elem.add(self.denoisedTomogramImage)
				if ( self.hideSideViews == false ){
					lazyTab.elem.add(TomoSideViewDenoiseImage(self.job.jobId, self.tiltSeries.id))
				}
			}
		}

		if ( self.showScores == true ) {
			addTab("Particle Picking") { lazyTab ->
					if ( self.hideDownloadBadges == false ){
						lazyTab.elem.add(self.scoresDownloadBadge)
					}
					lazyTab.elem.add(self.scoresTomogramImage)
					if ( self.showScoreMetrics == true ){
						lazyTab.elem.add(TomoPytomImages("Extraction Graph", "pytomTiltSeriesExtractionGraph", self.job.jobId, self.tiltSeries.id))
						lazyTab.elem.add(TomoPytomImages("ROC Estimation", "pytomTiltSeriesRoc", self.job.jobId, self.tiltSeries.id))
					}
				}
		}

		if ( self.showHeatMap == true ) {
			addTab("Heat Map") { lazyTab ->
					lazyTab.elem.add(self.heatMapTomogramImage)
				}
		}

	}

	suspend fun load() {

		recDownloadBadge.load()

		// load the particles image
		particlesImage.load()
		
		if ( showSegmented == true ) {
			segmentedTomogramImage.load()
			segmentedDownloadBadge.load()
		}

		if ( showDenoised == true ) {
			denoisedTomogramImage.load()
			denoisedDownloadBadge.load()
		}

		if ( showScores == true ) {
			scoresTomogramImage.load()
			scoresDownloadBadge.load()
		}

		if ( showHeatMap == true ) {
			heatMapTomogramImage.load()
		}
    }
}