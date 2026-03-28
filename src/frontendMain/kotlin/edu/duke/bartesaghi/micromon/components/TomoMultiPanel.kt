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
 * TODO: maybe someday we can merge TomoMultiPanel and SessionTomoMultiPanel together?
 */

class TomoMultiPanel(
	val project: ProjectData,
	val job: JobData,
	val tiltSerieses: TiltSeriesesData,
	val tiltSeries: TiltSeriesData,
	val pickingControls: MultiListParticleControls? = null,
	val tomoRecGenerateHalves: Boolean? = null,
	val half1Exists: Boolean = false,
	val half2Exists: Boolean = false
): Div(classes = setOf("tomo-multipanel")) {

	private var currentTiltFramesTab: Double? = null
	private var currentTiltCTFTab: Double? = null

	/** Called after tilt exclusions are successfully saved; parameter is tiltSeries.id. Use to refresh gallery overlay. */
	var onTiltExclusionChanged: ((String) -> Unit)? = null

	private val ctfMultiTiltPlot = CTFMultiTiltPlot("/kv/jobs/${job.jobId}/data/${tiltSeries.id}/2dCtfTiltMontage")
	private val driftBarPlot = FrameMotionDriftPlot("/kv/jobs/${job.jobId}/data/${tiltSeries.id}/rawTiltSeriesMontage")

	private val ctf1DPlot = CTF1DPlot(
		loader = {
			AvgRotData(emptyList(), emptyList(), emptyList(), emptyList(), 0.0)
		},
		titler = {
			if (currentTiltCTFTab == null) {
				"Select a point or hover over chart to view CTF data"
			} else {
				"CTF for tilt angle ${currentTiltCTFTab.toFixed(1)}\u00b0"
			}
		}
	)

	private val motionPlot = MotionPlot(
		loader = {
			MotionData(emptyList(), emptyList())
		},
		titler = {
			if (currentTiltFramesTab == null) {
				"Select a bar or hover over chart to view motion data"
			} else {
				"Motion for tilt angle ${currentTiltFramesTab.toFixed(1)}\u00b0"
			}
		}
	)

	val particlesImage = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, pickingControls)
	val virionThresholds = TomoVirionThresholds(project, job, tiltSeries)

	private val alignedTiltSeriesImage = PlayableSpritePanel(
		"/kv/jobs/${job.jobId}/data/${tiltSeries.id}/alignedTiltSeriesMontage",
		"Aligned Tilt Series",
		Storage::spriteAlignedTiltSeriesSize
	)

	val denoisedTomogramImage = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, spriteUrl = "/kv/jobs/${job.jobId}/data/${tiltSeries.id}/denoisedTomogramMontage")

	val segmentedTomogramImage = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, spriteUrl = "/kv/jobs/${job.jobId}/data/${tiltSeries.id}/segmentedTomogramMontage")

	private val ctf2dImage = SpritePanel(
		ctfMultiTiltPlot.ctf2dImageSource,
		"2D CTF Profile",
		Storage::spriteCtf2dPlotSize,
		"Select a point or hover over chart to view CTF data"
	)

	private val rawTiltSeriesImage = SpritePanel(
		driftBarPlot.rawTiltSeriesImageSource,
		"Tilted Image",
		Storage::spriteTiltSeriesMicrographSize,
		"Select a bar or hover over chart to view micrograph"
	)

	private val recDownloadBadge = FileDownloadBadge(
		filetype = ".rec file",
		url = "kv/jobs/${job.jobId}/data/${tiltSeries.id}/rec",
		filename = "${job.jobId}_${tiltSeries.id}.rec",
		loader = { Services.jobs.recData(job.jobId, tiltSeries.id) }
	)

	private val denoisedDownloadBadge = FileDownloadBadge(
		filetype = ".rec file",
		url = "kv/jobs/${job.jobId}/data/${tiltSeries.id}/den",
		filename = "${job.jobId}_${tiltSeries.id}_den.rec",
		loader = { Services.jobs.denData(job.jobId, tiltSeries.id) }
	)

	private val segmentedDownloadBadge = FileDownloadBadge(
		filetype = ".rec file",
		url = "kv/jobs/${job.jobId}/data/${tiltSeries.id}/seg",
		filename = "${job.jobId}_${tiltSeries.id}_seg.rec",
		loader = { Services.jobs.segData(job.jobId, tiltSeries.id) }
	)

	private val recDownloadBadgeHalf1 = FileDownloadBadge(
		filetype = "half1.rec file",
		url = "kv/jobs/${job.jobId}/data/${tiltSeries.id}/recHalf1",
		filename = "${job.jobId}_${tiltSeries.id}_half1.rec",
		loader = { Services.jobs.recDataHalf1(job.jobId, tiltSeries.id) }
	)

	private val recDownloadBadgeHalf2 = FileDownloadBadge(
		filetype = "half2.rec file",
		url = "kv/jobs/${job.jobId}/data/${tiltSeries.id}/recHalf2",
		filename = "${job.jobId}_${tiltSeries.id}_half2.rec",
		loader = { Services.jobs.recDataHalf2(job.jobId, tiltSeries.id) }
	)

	private val particlesImageHalf1 = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, pickingControls, spriteUrl = "/kv/jobs/${job.jobId}/data/${tiltSeries.id}/reconstructionTiltSeriesMontageHalf1")

	private val particlesImageHalf2 = TomoParticlesImage.forProject(project, job, tiltSerieses, tiltSeries, pickingControls, spriteUrl = "/kv/jobs/${job.jobId}/data/${tiltSeries.id}/reconstructionTiltSeriesMontageHalf2")

	val tabs = flatLazyTabPanel {

		// DSLs make it super annoying to get to the outer scope
		val self = this@TomoMultiPanel

		persistence = Storage::tomographyPreprocessingMultiPanelTabIndex

		addTab("Tilts") { lazyTab ->
			lazyTab.elem.add(self.driftBarPlot)
			lazyTab.elem.add(self.rawTiltSeriesImage)
			lazyTab.elem.add(self.motionPlot)
			self.driftBarPlot.replot()
			self.motionPlot.replot()
		}

		addTab("Alignment") { lazyTab ->
			lazyTab.elem.add(TiltSeriesMotionPlot(self.job, self.tiltSeries).apply {
				loadData()
			})
			lazyTab.elem.add(self.alignedTiltSeriesImage)
		}

		addTab("CTF") { lazyTab ->
			lazyTab.elem.add(self.ctfMultiTiltPlot)
			lazyTab.elem.add(self.ctf2dImage)
			lazyTab.elem.add(self.ctf1DPlot)
			self.ctfMultiTiltPlot.replot()
		}

		addTab("Tomogram") { lazyTab ->
			self.pickingControls?.let { lazyTab.elem.add(it) }
			lazyTab.elem.add(self.recDownloadBadge)
			lazyTab.elem.add(self.particlesImage)
			lazyTab.elem.add(TomoSideViewImage(self.job.jobId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims()))
		}

		if (self.half1Exists) {
			addTab("Half 1") { lazyTab ->
				lazyTab.elem.add(self.recDownloadBadgeHalf1)
				lazyTab.elem.add(self.particlesImageHalf1)
				lazyTab.elem.add(TomoSideViewImage(self.job.jobId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims(), pathSuffix = "Half1"))
			}
		}
		if (self.half2Exists) {
			addTab("Half 2") { lazyTab ->
				lazyTab.elem.add(self.recDownloadBadgeHalf2)
				lazyTab.elem.add(self.particlesImageHalf2)
				lazyTab.elem.add(TomoSideViewImage(self.job.jobId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims(), pathSuffix = "Half2"))
			}
		}

		if (self.tiltSerieses.particles is TiltSeriesesParticlesData.VirusMode) {
			addTab("Segmentation") { lazyTab ->
				lazyTab.elem.add(self.virionThresholds)
				if (!self.virionThresholds.hasSelectedVirion()) {
					self.virionThresholds.selectDefaultVirion()
				}
			}
		}

		if ( self.job is TomographyPreprocessingData || self.job is TomographyDenoisingEvalData ) {
			addTab("Denoised") { lazyTab ->
				lazyTab.elem.add(self.denoisedDownloadBadge)
				lazyTab.elem.add(self.denoisedTomogramImage)
				lazyTab.elem.add(TomoSideViewDenoiseImage(self.job.jobId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims()))
			}
		}

		if ( self.job is TomographyPreprocessingData || self.job is TomographySegmentationOpenData ) {
			addTab("Segmentation") { lazyTab ->
				lazyTab.elem.add(self.segmentedDownloadBadge)
				lazyTab.elem.add(self.segmentedTomogramImage)
				lazyTab.elem.add(TomoSideViewSegImage(self.job.jobId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims()))
			}
		}
	}

	suspend fun load() {

		// Retrieve data
		val metadata = Services.jobs.getDriftMetadata(job.jobId, tiltSeries.id)
			.unwrap()
		if (metadata != null) {

			// Alignment tab
			alignedTiltSeriesImage.load(metadata.tilts.size, metadata.tilts.size / 2 - 1) { sprite ->
				// after loading finishes, add a horizontal reference line to the alignment
				sprite.div(classes = setOf("tilt-reference-line")) {
					div() // NOTE: this div is the line itself, the outer div is a container
				}
			}

			// CTF tab
			ctfMultiTiltPlot.myOnClick = { index ->
				currentTiltCTFTab = metadata.tilts.getOrNull(index)
				metadata.ctfProfiles.getOrNull(index)?.let { ctf1DPlot.setData(it) }
				if (!ctf2dImage.showing) {
					ctf2dImage.myShow(index)
				} else {
					ctf2dImage.sprite?.index = index
				}
				val ctfValue = metadata.ctfValues.getOrNull(index)
				ctf2dImage.setStatusText(
					"DF1 ${ctfValue?.let { it.defocus1.toFixed(1) } ?: "?"} A, " +
					"DF2 ${ctfValue?.let { it.defocus2.toFixed(1) } ?: "?"} A, " +
					"Angast ${ctfValue?.let { it.astigmatism.toFixed(1) } ?: "?"}\u00b0, " +
					"Score ${ctfValue?.let { it.cc.toFixed(2) } ?: "?"}"
				)
			}
			ctf2dImage.load(metadata.tilts.size)
			ctfMultiTiltPlot.setData(metadata)

			// reshape the point lists into a list of struct-of-arrays
			val motionData = metadata.drifts.map { points ->
				MotionData(
					x = points.map { it.x },
					y = points.map { it.y }
				)
			}

			// Movie frames (Tilts) tab
			driftBarPlot.myOnClick = { index ->
				metadata.tilts.getOrNull(index)?.let { currentTiltFramesTab = it }
				motionData.getOrNull(index)?.let { motionPlot.setData(it) }
				if (!rawTiltSeriesImage.showing) {
					rawTiltSeriesImage.myShow(index)
				} else {
					rawTiltSeriesImage.sprite?.index = index
				}
			}
			rawTiltSeriesImage.load(metadata.tilts.size)
			rawTiltSeriesImage.sprite?.let { sprite ->

				// add the tilt line before the sprite image
				val line = Div(classes = setOf("tilt-angle-line")) {
					div {
						setStyle("transform", "rotate(${90.0 + metadata.tiltAxisAngle}deg)")
					}
				}
				sprite.addBefore(line, sprite.img)
			}
			rawTiltSeriesImage.setStatusText("Tilt axis angle ${metadata.tiltAxisAngle.toFixed(1)}\u00b0")

			driftBarPlot.setData(metadata, motionData, Services.jobs.getTiltExclusions(job.jobId, tiltSeries.id))
			driftBarPlot.onSaveTiltExclusion = { index, value ->
				// save the tilt exclusion to the server
				AppScope.launch {
					val toastOptions = ToastOptions(positionClass = ToastPosition.BOTTOMRIGHT)
					try {
						Services.jobs.setTiltExclusion(job.jobId, tiltSeries.id, index, value)
						onTiltExclusionChanged?.invoke(tiltSeries.id)
						Toast.success(
							message = if (value) {
								"Excluded tilt $index"
							} else {
								"Included tilt $index"
							},
							options = toastOptions
						)
					} catch (err: dynamic) {
						Toast.error(
							message = err.toString(),
							options = toastOptions
						)
					}
				}
			}

			// Try to find the zero-tilt data, if possible
			val initialIndexToShow = metadata.tilts.indexOf(0.0)
				.takeIf { it >= 0 }
				?: run {
					// otherwise, pick the smallest tilt available
					metadata.tilts.withIndex()
						.minByOrNull { (_, tilt) -> abs(tilt) }
						?.let { (i, _) -> i }
				}
			if (initialIndexToShow != null) {
				ctfMultiTiltPlot.myOnClick(initialIndexToShow)
				driftBarPlot.myOnClick(initialIndexToShow)
			}
		}

		val condition = true

		recDownloadBadge.load()
		if ( condition == true ) {
			denoisedDownloadBadge.load()
			segmentedDownloadBadge.load()
		}
		if (half1Exists) {
			recDownloadBadgeHalf1.load()
		}
		if (half2Exists) {
			recDownloadBadgeHalf2.load()
		}

		// load the particles image
		particlesImage.load()
		if ( condition == true ){
			denoisedTomogramImage.load()
			segmentedTomogramImage.load()
		}
		if (half1Exists) {
			particlesImageHalf1.load()
		}
		if (half2Exists) {
			particlesImageHalf2.load()
		}

		// load thresholds for the threshold picker, if needed
		virionThresholds.load()
    }
}
