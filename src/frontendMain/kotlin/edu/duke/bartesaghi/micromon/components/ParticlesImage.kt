package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.*
import io.kvision.html.Div
import io.kvision.html.Image
import io.kvision.html.div
import io.kvision.utils.px
import io.kvision.core.onEvent
import io.kvision.core.onClick
import io.kvision.form.check.CheckBox
import js.clickRelativeTo
import org.w3c.dom.events.MouseEvent
import kotlin.reflect.KMutableProperty0


/**
 * Shows an image in a resizable panel,
 * with a checkbox to show the picked particles.
 */
open class ParticlesImage(
	title: String,
	sizeStorage: KMutableProperty0<ImageSize?>,
	val imageUrl: (ImageSize) -> String,
	val ownerType: OwnerType,
	val ownerId: String,
	val datumId: String,
	val imagesScale: ImagesScale?,
	val sourceDims: ImageDims?,
	val particleControls: ParticleControls,
	val newParticleRadiusA: Double?
) : SizedPanel(title, sizeStorage.get()) {

	val scaler = Scaler.of(imagesScale, sourceDims)
	val scaleBar = ScaleBar(scaler)

	var onParticlesChange: (() -> Unit)? = null

	private val imageElem = Image("")
	private val imageContainerElem = div {

		// position this container relatively, so the particle markers can be positioned absolutely
		position = Position.RELATIVE

		// ugh, Kotlin DSLs make it hard to access outer scopes inside DSLs
		val pi = this@ParticlesImage

		// show the image and the scale bar
		add(pi.imageElem)
		add(pi.scaleBar)
	}

	private var particles: MutableMap<Int,Particle2D>? = null
	private val markersByParticleId = HashMap<Int,ParticleMarker>()

	private val showParticlesCheck = CheckBox(
		value = Storage.showParticles ?: true,
		label = "particles"
	)

	// to avoid compiler warnings about open functions being called from constructors
	final override fun add(child: Component) = super.add(child)

	init {

		// add all the components to the panel
		add(imageContainerElem)
		add(showParticlesCheck)

		// wire up events
		showParticlesCheck.onEvent {
			change = {
				Storage.showParticles = showParticlesCheck.value
				updateParticles()
			}
		}
		imageContainerElem.onClick { event ->
			AppScope.launch {
				this@ParticlesImage.onImageClick(event)
			}
		}
		particleControls.onListChange = {
			AppScope.launch {

				loadParticlesAsync()
				updateParticlesCheckbox()
				updateParticles()

				// bubble events up to the view
				onParticlesChange?.invoke()
			}
		}

		// load the micrograph image
		imageElem.src = imageUrl(size)

		// set the panel resize handler
		onResize = { newSize: ImageSize ->

			// save the new size
			sizeStorage.set(newSize)

			// update the image
			imageElem.src = imageUrl(newSize)
		}

		// add the measure tool
		scaler?.let {
			rightDiv.add(0, MeasureTool.button(imageContainerElem, it, MeasureTool.showIn(scaleBar)))
		}
	}

	fun loadParticles() {
		AppScope.launch {
			loadParticlesAsync()
			updateParticlesCheckbox()
			updateParticles()
		}
	}

	private suspend fun loadParticlesAsync() {

		val particlesList = particleControls.list
			?: return

		particles = Services.particles.getParticles2D(ownerType, ownerId, particlesList.name, datumId)
			.toMap()
			.toMutableMap()
	}


	private fun Particle2D.makeMarker(particleId: Int) {

		val scaler = scaler
			?: return

		val marker = ParticleMarker(
			onClick = {
				// do nothing, just prevent the usual click handler from happening
				// so we don't create particles on top of each other
			},
			onRightClick = {
				AppScope.launch {
					deleteParticle(particleId)
				}
			}
		)
		markersByParticleId[particleId] = marker

		imageContainerElem.add(marker)
		marker.place(
			x.unbinnedToNormalizedX(scaler),
			y.unbinnedToNormalizedY(scaler),
			r.unbinnedToNormalizedX(scaler),
			r.unbinnedToNormalizedY(scaler)
		)
	}

	private fun updateParticlesCheckbox() {

		// count the particles, if any
		val numParticles = particles?.size
		if (numParticles != null) {
			showParticlesCheck.label = "Show ${numParticles.formatWithDigitGroupsSeparator()} particles"
			showParticlesCheck.enabled = true
		} else {
			showParticlesCheck.label = "No particles to show"
			showParticlesCheck.enabled = false
		}
	}

	/**
	 * Shows the currently-selected particles, if any
	 */
	private fun updateParticles() {

		// clear any previous markers
		batch {
			markersByParticleId.values.forEach { imageContainerElem.remove(it) }
		}
		markersByParticleId.clear()

		// we're done already if we don't need to add any new markers
		if (!showParticlesCheck.value) {
			return
		}

		val particles = particles
			?: return

		// create the particle markers
		batch {
			for ((particleId, particle) in particles) {
				particle.makeMarker(particleId)
			}
		}
	}

	private suspend fun onImageClick(event: MouseEvent) {

		if (!particleControls.canWrite) {
			return
		}
		if (particleControls.list?.source != ParticlesSource.User) {
			return
		}

		val particles = particles
			?: return

		val scaler = scaler
			?: return
		val radius = newParticleRadiusA
			?.aToUnbinned(scaler)
			?: return
		val click = event.clickRelativeTo(imageContainerElem, true)
			?: return

		// we clicked in the particles area, turn on the particle markers
		showParticlesCheck.value = true
		Storage.showParticles = true

		// convert mouse coords from screen space to unbinned pixels space
		val particle = Particle2D(
			x = click.x.normalizedToUnbinnedX(scaler),
			y = click.y.flipNormalized().normalizedToUnbinnedY(scaler),
			r = radius
		)

		// make a new particle and save the changes on the server
		val particleId = particleControls.addParticle2D(datumId, particle)
			?: return
		particles[particleId] = particle

		// make a new marker too
		particle.makeMarker(particleId)

		updateParticlesCheckbox()

		// bubble events up to the view
		onParticlesChange?.invoke()
	}

	private suspend fun deleteParticle(particleId: Int) {

		if (!particleControls.canWrite) {
			return
		}
		if (particleControls.list?.source != ParticlesSource.User) {
			return
		}

		val particles = particles
			?: return

		// try to remove the particle from the server
		particleControls.removeParticle(datumId, particleId)
			.takeIf { it }
			?: return

		// remove the particle locally
		particles.remove(particleId)

		// remove the marker
		markersByParticleId[particleId]
			?.let { imageContainerElem.remove(it) }

		updateParticlesCheckbox()

		// bubble events up to the view
		onParticlesChange?.invoke()
	}
}


class MicrographImage(
	val project: ProjectData,
	val job: JobData,
	val micrograph: MicrographMetadata,
	imagesScale: ImagesScale?,
	particleControls: MultiListParticleControls,
	newParticleRadiusA: Double?
) : ParticlesImage(
	"Micrograph",
	Storage::micrographSize,
	imageUrl = { size -> micrograph.imageUrl(job, size) },
	ownerType = OwnerType.Project,
	ownerId = job.jobId,
	datumId = micrograph.id,
	imagesScale,
	micrograph.sourceDims,
	particleControls,
	newParticleRadiusA
)


class TiltSeriesImage(
	val project: ProjectData,
	val job: JobData,
	val tiltSeries: TiltSeriesData,
	imagesScale: ImagesScale?
) : ParticlesImage(
	"TiltSeries",
	Storage::tiltSeriesSize,
	imageUrl = { size -> tiltSeries.imageUrl(job, size) },
	ownerType = OwnerType.Project,
	ownerId = job.jobId,
	datumId = tiltSeries.id,
	imagesScale,
	tiltSeries.sourceDims,
	NoneParticleControls(),
	null
)


class SessionMicrographImage(
	val session: SessionData,
	val micrograph: MicrographMetadata,
	imagesScale: ImagesScale?
) : ParticlesImage(
	"Micrograph",
	Storage::micrographSize,
	imageUrl = { size -> micrograph.imageUrl(session, size) },
	ownerType = OwnerType.Session,
	ownerId = session.sessionId,
	datumId = micrograph.id,
	imagesScale,
	micrograph.sourceDims,
	ShowListParticleControls(ParticlesList.autoParticles2D(session.sessionId)),
	null
)


class SessionTiltSeriesImage(
	val session: SessionData,
	val tiltSeries: TiltSeriesData,
	imagesScale: ImagesScale?
) : ParticlesImage(
	"Tilt Series",
	Storage::tiltSeriesSize,
	imageUrl = { size -> tiltSeries.imageUrl(session, size) },
	ownerType = OwnerType.Session,
	ownerId = session.sessionId,
	datumId = tiltSeries.id,
	imagesScale,
	tiltSeries.sourceDims,
	NoneParticleControls(),
	null
)


class ParticleMarker(
	onClick: (() -> Unit)? = null,
	onRightClick: (() -> Unit)? = null,
	color: Color = Color.hex(0x00ff00)
) : Div(
	// Sadly, KVision doesn't seem to support the border-radius style,
	// so we'll have to use a CSS class to set it.
	// The downside is we have to use the same border-radius for all
	// the markers, regardless of the marker's actual size.
	// luckily, we can just set the radius to a huge number
	// and it will still look for any maker.
	classes = setOf("circular")
) {

	init {
		display = Display.INLINEBLOCK
		position = Position.ABSOLUTE
		border = Border(
			// 1 px is a bit too thin to see well
			width = 2.px,
			style = BorderStyle.SOLID,
			color = color
		)

		// wire up events
		if (onClick != null) {
			onClick {
				it.stopPropagation()
				onClick()
			}
		}
		if (onRightClick != null) {
			onEvent {
				contextmenu = { event ->
					event.preventDefault()
					onRightClick()
				}
			}
		}
	}

	/**
	 * Places the marker on the given element at the specified position.
	 * x, y, radiusx, and radiusy are given in normalized coordinates, with the y axis pointing up.
	 * The marker will be placed such that the center lies on the given coordinates.
	 * Since the coordinates are normalized, the radius must be given in x and y components.
	 */
	fun place(x: Double, y: Double, radiusx: Double, radiusy: Double) {

		// place the marker at the upper left corner of the particle bounding box
		this.left = (x - radiusx).normalizedToPercent()
		this.top = (y.flipNormalized() - radiusy).normalizedToPercent()

		// set the size to be twice the radius
		this.width = (radiusx*2).normalizedToPercent()
		this.height = (radiusy*2).normalizedToPercent()
	}
}
