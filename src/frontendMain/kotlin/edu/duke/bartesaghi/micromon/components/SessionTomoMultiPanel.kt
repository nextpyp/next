package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.pyp.TiltSeriesesData
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.toFixed
import io.kvision.html.Div
import io.kvision.html.div
import kotlin.math.abs

class SessionTomoMultiPanel(
	val session: SessionData,
	val tiltSerieses: TiltSeriesesData,
	val tiltSeries: TiltSeriesData,
	val denoise: Boolean = false,
	val segmentation: Boolean = false,
	val half1Exists: Boolean = false,
	val half2Exists: Boolean = false
): Div(classes = setOf("tomo-multipanel")) {

	private var currentTiltFramesTab: Double? = null
	private var currentTiltCTFTab: Double? = null

	private val ctfMultiTiltPlot = CTFMultiTiltPlot("/kv/tomographySession/${session.sessionId}/${tiltSeries.id}/2dCtfTiltMontage")
	private val driftBarPlot = FrameMotionDriftPlot("/kv/tomographySession/${session.sessionId}/${tiltSeries.id}/rawTiltSeriesMontage")

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

	val particlesImage = TomoParticlesImage.forSession(session, tiltSerieses, tiltSeries)

	private val alignedTiltSeriesImage = PlayableSpritePanel(
		"/kv/tomographySession/${session.sessionId}/${tiltSeries.id}/alignedTiltSeriesMontage",
		"Aligned Tilt Series",
		Storage::spriteAlignedTiltSeriesSize
	)

	val denoisedTomogramImage = TomoParticlesImage.forSession(session, tiltSerieses, tiltSeries, spriteUrl = "/kv/tomographySession/${session.sessionId}/${tiltSeries.id}/denoisedTomogramMontage", placeholderOnError = true)

	val segmentedTomogramImage = TomoParticlesImage.forSession(session, tiltSerieses, tiltSeries, spriteUrl = "/kv/tomographySession/${session.sessionId}/${tiltSeries.id}/segmentedTomogramMontage", placeholderOnError = true)

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
		url = "kv/tomographySession/${session.sessionId}/${tiltSeries.id}/rec",
		filename = "${session.sessionId}_${tiltSeries.id}.rec",
		loader = { Services.tomographySessions.recData(session.sessionId, tiltSeries.id) }
	)

	private val denoiseDownloadBadge = FileDownloadBadge(
		filetype = ".rec file",
		url = "kv/tomographySession/${session.sessionId}/${tiltSeries.id}/den",
		filename = "${session.sessionId}_${tiltSeries.id}_den.rec",
		loader = { Services.tomographySessions.denData(session.sessionId, tiltSeries.id) }
	)

	private val segDownloadBadge = FileDownloadBadge(
		filetype = ".rec file",
		url = "kv/tomographySession/${session.sessionId}/${tiltSeries.id}/seg",
		filename = "${session.sessionId}_${tiltSeries.id}_seg.rec",
		loader = { Services.tomographySessions.segData(session.sessionId, tiltSeries.id) }
	)

	private val recDownloadBadgeHalf1 = FileDownloadBadge(
		filetype = "half1.rec file",
		url = "kv/tomographySession/${session.sessionId}/${tiltSeries.id}/recHalf1",
		filename = "${session.sessionId}_${tiltSeries.id}_half1.rec",
		loader = { Services.tomographySessions.recDataHalf1(session.sessionId, tiltSeries.id) }
	)

	private val recDownloadBadgeHalf2 = FileDownloadBadge(
		filetype = "half2.rec file",
		url = "kv/tomographySession/${session.sessionId}/${tiltSeries.id}/recHalf2",
		filename = "${session.sessionId}_${tiltSeries.id}_half2.rec",
		loader = { Services.tomographySessions.recDataHalf2(session.sessionId, tiltSeries.id) }
	)

	private val particlesImageHalf1 = TomoParticlesImage.forSession(session, tiltSerieses, tiltSeries, spriteUrl = "/kv/tomographySession/${session.sessionId}/${tiltSeries.id}/reconstructionTiltSeriesMontageHalf1", placeholderOnError = true)

	private val particlesImageHalf2 = TomoParticlesImage.forSession(session, tiltSerieses, tiltSeries, spriteUrl = "/kv/tomographySession/${session.sessionId}/${tiltSeries.id}/reconstructionTiltSeriesMontageHalf2", placeholderOnError = true)

	val tabs = flatLazyTabPanel {

		// DSLs make it super annoying to get to the outer scope
		val self = this@SessionTomoMultiPanel

		persistence = Storage::tomographyPreprocessingMultiPanelTabIndex

		addTab("Tilts") { lazyTab ->
			lazyTab.elem.add(self.driftBarPlot)
			lazyTab.elem.add(self.rawTiltSeriesImage)
			lazyTab.elem.add(self.motionPlot)
			self.driftBarPlot.replot()
			self.motionPlot.replot()
		}

		addTab("Alignment") { lazyTab ->
			lazyTab.elem.add(SessionTiltSeriesMotionPlot(self.session, self.tiltSeries).apply {
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

		addTab("Reconstruction") { lazyTab ->
			lazyTab.elem.add(self.recDownloadBadge)
			lazyTab.elem.add(self.particlesImage)
			lazyTab.elem.add(SessionTomoSideViewImage(self.session.sessionId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims()))
		}

		if (self.half1Exists) {
			addTab("Half 1") { lazyTab ->
				lazyTab.elem.add(self.recDownloadBadgeHalf1)
				lazyTab.elem.add(self.particlesImageHalf1)
				lazyTab.elem.add(SessionTomoSideViewImage(self.session.sessionId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims(), pathSuffix = "Half1"))
			}
		}
		if (self.half2Exists) {
			addTab("Half 2") { lazyTab ->
				lazyTab.elem.add(self.recDownloadBadgeHalf2)
				lazyTab.elem.add(self.particlesImageHalf2)
				lazyTab.elem.add(SessionTomoSideViewImage(self.session.sessionId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims(), pathSuffix = "Half2"))
			}
		}

		if (self.denoise) {
			addTab("Denoised") { lazyTab ->
				lazyTab.elem.add(self.denoiseDownloadBadge)
				lazyTab.elem.add(self.denoisedTomogramImage)
				lazyTab.elem.add(SessionTomoSideViewDenoiseImage(self.session.sessionId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims()))
			}
		}

		if (self.segmentation) {
			addTab("Segmentation") { lazyTab ->
				lazyTab.elem.add(self.segDownloadBadge)
				lazyTab.elem.add(self.segmentedTomogramImage)
				lazyTab.elem.add(SessionTomoSideViewSegImage(self.session.sessionId, self.tiltSeries.id, self.tiltSeries.imageDims.sideViewDims()))
			}
		}
	}

	suspend fun load() {

		// Retrieve data
		val metadata = Services.tomographySessions.getDriftMetadata(session.sessionId, tiltSeries.id)
			.unwrap()
		if (metadata != null) {
			val motionData = metadata.drifts.map { MotionData(it.map { xy -> xy.x }, it.map { xy -> xy.y }) }

			val initialIndexToShow = run {
				// Try to find the zero-tilt data, if possible
				val tempIndex = metadata.tilts.indexOf(0.0)
				// Otherwise, use the smallest tilt available
				if (tempIndex >= 0) tempIndex else metadata.tilts.indexOf(metadata.tilts.minByOrNull { abs(it) })
			}

			// Alignment tab
			alignedTiltSeriesImage.load(metadata.tilts.size, metadata.tilts.size / 2 - 1) { sprite ->
				// after loading finishes, add a horizontal reference line to the alignment
				sprite.div(classes = setOf("tilt-reference-line")) {
					div() // NOTE: this div is the line itself, the outer div is a container
				}
			}

			// CTF tab
			ctfMultiTiltPlot.myOnClick = { index ->
				currentTiltCTFTab = metadata.tilts[index]
				ctf1DPlot.setData(metadata.ctfProfiles[index])
				if (!ctf2dImage.showing) {
					ctf2dImage.myShow(index)
				}
				else ctf2dImage.sprite?.index = index
				ctf2dImage.setStatusText(
					"DF1 ${metadata.ctfValues[index].defocus1.toFixed(1)} A, " +
					"DF2 ${metadata.ctfValues[index].defocus2.toFixed(1)} A, " +
					"Angast ${metadata.ctfValues[index].astigmatism.toFixed(1)}\u00b0, " +
					"Score ${metadata.ctfValues[index].cc.toFixed(2)}"
				)
			}
			ctf2dImage.load(metadata.tilts.size)
			ctfMultiTiltPlot.setData(metadata)

			// Movie frames (Tilts) tab
			driftBarPlot.myOnClick = { index ->
				currentTiltFramesTab = metadata.tilts[index]
				motionPlot.setData(motionData[index])
				if (!rawTiltSeriesImage.showing) {
					rawTiltSeriesImage.myShow(index)
				}
				else rawTiltSeriesImage.sprite?.index = index
			}
			rawTiltSeriesImage.load(metadata.tilts.size)
			rawTiltSeriesImage.sprite?.let { it.div(classes = setOf("tilt-angle-line")).div().apply {
				this.setStyle("transform", "rotate(${90.0 + metadata.tiltAxisAngle}deg)")
				it.add(it.img) // Move the image element after the tilt angle line (i.e. reorder the DOM)
							   // Ideally, there would have been an insertBefore method for this...
			} }
			rawTiltSeriesImage.setStatusText("Tilt axis angle ${metadata.tiltAxisAngle.toFixed(1)}\u00b0")

			driftBarPlot.setData(metadata, motionData, null)

			// Load the data corresponding to the initial index we want to show, determined above
			ctfMultiTiltPlot.myOnClick(initialIndexToShow)
			driftBarPlot.myOnClick(initialIndexToShow)
		}

		recDownloadBadge.load()
		if (half1Exists) {
			recDownloadBadgeHalf1.load()
		}
		if (half2Exists) {
			recDownloadBadgeHalf2.load()
		}
		if (denoise) {
			denoiseDownloadBadge.load()
		}
		if (segmentation) {
			segDownloadBadge.load()
		}

		// load the particles image
		particlesImage.load()
		if (half1Exists) {
			particlesImageHalf1.load()
		}
		if (half2Exists) {
			particlesImageHalf2.load()
		}
		if (denoise) {
			denoisedTomogramImage.load()
		}
		if (segmentation) {
			segmentedTomogramImage.load()
		}
	}
}
