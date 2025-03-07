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
	val pickingControls: MultiListParticleControls? = null
): Div(classes = setOf("tomo-multipanel")) {

	private var currentTiltFramesTab: Double? = null
	private var currentTiltCTFTab: Double? = null

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
			lazyTab.elem.add(TomoSideViewImage(self.job.jobId, self.tiltSeries.id))
		}

		if (self.tiltSerieses.particles is TiltSeriesesParticlesData.VirusMode) {
			addTab("Segmentation") { lazyTab ->
				lazyTab.elem.add(self.virionThresholds)
				if (!self.virionThresholds.hasSelectedVirion()) {
					self.virionThresholds.selectDefaultVirion()
				}
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

		recDownloadBadge.load()

		// load the particles image
		particlesImage.load()

		// load thresholds for the threshold picker, if needed
		virionThresholds.load()
    }
}
