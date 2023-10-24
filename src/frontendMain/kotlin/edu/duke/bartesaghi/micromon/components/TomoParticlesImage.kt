package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.batch
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.pyp.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.*
import io.kvision.form.check.CheckBox
import io.kvision.form.check.CheckBoxStyle
import io.kvision.html.Div
import js.clickRelativeTo
import org.w3c.dom.events.MouseEvent
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.reflect.KMutableProperty0


/**
 * Shows the Tilt Series Images and a checkbox to show/hide particles.
 * Provides functionality to add/delete markers.
 */
class TomoParticlesImage(
	val tiltSerieses: TiltSeriesesData,
	val tiltSeries: TiltSeriesData,
	val particleControls: ParticleControls,
	val editable: Boolean,
	val spriteUrl: String,
	val ownerType: OwnerType,
	val ownerId: String
) : Div(classes = setOf("tomo-sized-panel")) {

	companion object {

		fun forProject(
			project: ProjectData,
			job: JobData,
			tiltSerieses: TiltSeriesesData,
			tiltSeries: TiltSeriesData,
			particleControls: ProjectParticleControls
		) =
			TomoParticlesImage(
				tiltSerieses,
				tiltSeries,
				particleControls,
				editable = project.canWrite(),
				spriteUrl = "/kv/jobs/${job.jobId}/data/${tiltSeries.id}/reconstructionTiltSeriesMontage",
				ownerType = OwnerType.Project,
				ownerId = job.jobId
			)

		fun forSession(
			session: SessionData,
			tiltSerieses: TiltSeriesesData,
			tiltSeries: TiltSeriesData
		) =
			TomoParticlesImage(
				tiltSerieses,
				tiltSeries,
				if (tiltSerieses.virusMode != null) {
					ShowListParticleControls(ParticlesList.autoVirions(session.sessionId))
				} else {
					ShowListParticleControls(ParticlesList.autoParticles2D(session.sessionId))
				},
				editable = false,
				spriteUrl = "/kv/tomographySession/${session.sessionId}/${tiltSeries.id}/reconstructionTiltSeriesMontage",
				ownerType = OwnerType.Session,
				ownerId = session.sessionId
			)
	}

	private val scaler = Scaler.of(tiltSerieses.imagesScale, tiltSeries.sourceDims)

	var onParticlesChange: (() -> Unit)? = null

	private class ParticlesInfo(
		val list: ParticlesList,
		val color: Color,
		val checkbox: ParticlesCheckBox,
		val editable: Boolean,
		/** particles are in binned coordinates */
		val particles: MutableMap<Int,Particle3D>,
		val newParticleRadiusA: Double?,
		val extraRadiusBinning: Int
	) {
		val markersByParticleId = HashMap<Int,ParticleMarker>()
	}
	private val particlesInfos = ArrayList<ParticlesInfo>()
	private val particlesInfosToCleanup = ArrayList<ParticlesInfo>()

	private inner class ParticlesCheckBox(
		val namePlural: String,
		val storage: KMutableProperty0<Boolean?>
	) : CheckBox() {

		init {

			value = storage.get() ?: true
			style = CheckBoxStyle.PRIMARY

			// wire up events
			onEvent {
				change = {
					storage.set(value)
					updateParticles()
				}
			}

			update(null)
		}

		fun update(info: ParticlesInfo?) {
			if (info != null) {
				val typeName = when (info.list.source) {
					ParticlesSource.Pyp -> "auto"
					ParticlesSource.User -> "picked"
				}
				label = "Show ${info.particles.size.formatWithDigitGroupsSeparator()} $typeName $namePlural"
				enabled = true
			} else {
				label = "No $namePlural to show"
				enabled = false
			}
		}
	}

	private val showVirionsCheck = ParticlesCheckBox("virions", Storage::showVirions)
	private val showSpikesCheck = ParticlesCheckBox("spikes", Storage::showSpikes)
    private val showParticlesCheck = ParticlesCheckBox("particles", Storage::showParticles)

    private val playableSprite = PlayableSpritePanel(
        spriteUrl,
        "Tomogram Slices",
        Storage::spriteReconstructionTiltSeriesSize,
        onIndexChange = {
            updateParticles()
        },
        onImageSizeChange = {
			updateParticles()
		}
    )

    init {

		// listen to events from the picking controls
		particleControls.onListChange = {
			AppScope.launch {

				loadParticlesAsync()
				updateParticlesCheckboxes()
				updateParticles()

				// bubble events up to the view
				onParticlesChange?.invoke()
			}
		}

        add(playableSprite)

		// add the checkboxes
		if (tiltSerieses.virusMode != null) {
			add(showVirionsCheck)
			add(showSpikesCheck)
		} else {
			add(showParticlesCheck)
		}

        // add keyboard shortcuts to control the slice selection
        onEvent {
            keydown = e@{ event ->
                val controls = playableSprite.playable ?: return@e
                when (event.key) {
                    "ArrowLeft" -> controls.currentIndex -= 1
                    "ArrowRight" -> controls.currentIndex += 1
                    "ArrowUp" -> controls.currentIndex = controls.indexRange.last
                    "ArrowDown" -> controls.currentIndex = controls.indexRange.first
                    else -> return@e
                }

                // try to prevent the default browser behavior that scrolls the page up and down
                // TODO: this doesn't seem to be working?? the page still scrolls on up,down key presses
                event.stopPropagation()
                event.preventDefault()
            }
        }

        // give the div a tab index, so it can accept keyboard focus
        tabindex = 5 // chosen by fair die roll, guaranteed to be random
    }

    fun load() {

		// load the tomogram slices, start in the middle
		playableSprite.load(Scaler.NUM_SLICES, Scaler.NUM_SLICES/2 - 1) { sprite ->

			// show the scale bar
			sprite.add(ScaleBar(scaler))

			// attach the click handler to the sprite image
			sprite.onClick { event ->
				AppScope.launch {
					this@TomoParticlesImage.onSpriteClick(sprite, event)
				}
			}

			AppScope.launch {
				loadParticlesAsync()
				updateParticlesCheckboxes()
				updateParticles()
			}
		}
    }

	fun loadParticles() {
		AppScope.launch {
			loadParticlesAsync()
			updateParticlesCheckboxes()
			updateParticles()
		}
	}

	private suspend fun loadParticlesAsync() {

		// move the current infos to the cleanup area
		particlesInfosToCleanup.addAll(particlesInfos)
		particlesInfos.clear()

		suspend fun getList(name: String): ParticlesList? =
			Services.particles.getList(ownerType, ownerId, name)
				.unwrap()

		suspend fun getParticles(name: String) =
			Services.particles.getParticles3D(ownerType, ownerId, name, tiltSeries.id)
				.toMap()
				.toMutableMap()

		val virusMode = tiltSerieses.virusMode
		if (virusMode != null) {

			// in virus mode, the picked particles are virions
			val virionsInfo = particleControls.list
				?.let { particlesList ->
					ParticlesInfo(
						list = particlesList,
						color = Colors.green,
						checkbox = showVirionsCheck,
						editable = true,
						particles = getParticles(particlesList.name),
						newParticleRadiusA = virusMode.virionRadiusA,
						extraRadiusBinning = virusMode.virionBinning
					)
				}
			virionsInfo?.let { particlesInfos.add(it) }

			// if there are any auto particles, show auto those too as spikes
			val spikesInfo = getList(ParticlesList.PypAutoParticles)
				?.let { particlesList ->
					ParticlesInfo(
						list = particlesList,
						color = Colors.blue,
						checkbox = showSpikesCheck,
						editable = true,
						particles = getParticles(particlesList.name),
						newParticleRadiusA = scaler?.scale?.particleRadiusA,
						extraRadiusBinning = 1
					)
				}
			spikesInfo?.let { particlesInfos.add(it) }

		} else {

			// use the picked particles, if any
			val particlesInfo = particleControls.list
				?.let { particlesList ->
					ParticlesInfo(
						list = particlesList,
						color = Colors.green,
						checkbox = showParticlesCheck,
						editable = true,
						particles = getParticles(particlesList.name),
						newParticleRadiusA = scaler?.scale?.particleRadiusA,
						extraRadiusBinning = 1
					)
				}
			particlesInfo?.let { particlesInfos.add(it) }
		}
	}

	private fun ParticlesInfo.makeMarker(particleId: Int, particle: Particle3D, zSliceBinned: Double, scaler: Scaler) {

		// apply extra radius binning
		val r = particle.r
			.binnedToUnbinned(scaler, extraRadiusBinning)
			.unbinnedToBinned(scaler)

		// slice the particle sphere at the z-coordinate of the tomogram slice to get the reduced radius
		val dz = abs(particle.z - zSliceBinned)
		val square = r*r - dz*dz
		if (square < 0) {
			return
		}
		val sliceRadiusBinned = sqrt(square)

		val marker = ParticleMarker(
			onClick = {
				// do nothing, just prevent the usual click handler from happening
				// so we don't create particles on top of each other
			},
			onRightClick = {
				AppScope.launch {
					deleteParticle(particleId)
				}
			},
			color = color
		)
		markersByParticleId[particleId] = marker

		playableSprite.sprite?.add(marker)
		marker.place(
			particle.x.binnedToNormalizedX(scaler),
			particle.y.binnedToNormalizedY(scaler),
			sliceRadiusBinned.binnedToNormalizedX(scaler),
			sliceRadiusBinned.binnedToNormalizedY(scaler)
		)
	}

	private fun ParticlesInfo.createMarkersInRange(zSliceBinned: Double, scaler: Scaler) {

		// make markers for all coords within the z-range of the slice
		for ((particleId, particle) in particles) {

			// apply extra radius binning
			val r = particle.r
				.binnedToUnbinned(scaler, extraRadiusBinning)
				.unbinnedToBinned(scaler)

			// skip coords that are out of range
			val z = particle.z // in binned voxels
			val dz = z - zSliceBinned
			if (dz*dz > r*r) {
				continue
			}

			makeMarker(particleId, particle, zSliceBinned, scaler)
		}
	}

	private fun updateParticlesCheckboxes() {

		val virusMode = tiltSerieses.virusMode
		if (virusMode != null) {

			val virionsInfo = particlesInfos
				.find { it.list.type == ParticlesType.Virions3D }
			val spikesInfo = particlesInfos
				.find { it.list.type == ParticlesType.Particles3D }

			showVirionsCheck.update(virionsInfo)
			showSpikesCheck.update(spikesInfo)
			showParticlesCheck.update(null)

		} else {

			val particlesInfo = particlesInfos
				.find { it.list.type == ParticlesType.Particles3D }

			showVirionsCheck.update(null)
			showSpikesCheck.update(null)
			showParticlesCheck.update(particlesInfo)
		}
	}

	private fun updateParticles() {

		val sprite = playableSprite.sprite
			?: return

		// clear any previous particles
		batch {
			for (info in particlesInfos) {
				info.markersByParticleId.values.forEach { sprite.remove(it) }
				info.markersByParticleId.clear()
			}
			for (info in particlesInfosToCleanup) {
				info.markersByParticleId.values.forEach { sprite.remove(it) }
				info.markersByParticleId.clear()
			}
			particlesInfosToCleanup.clear()
		}

		val scaler = scaler
			?: return

		// get the z-coordinate of the current slice, in binned voxels
		val zSliceBinned = sprite.index.sliceToBinnedZ(scaler)

		// create markers for all the particles in range of the z slice
		batch {
			for (info in particlesInfos) {
				if (info.checkbox.value) {
					info.createMarkersInRange(zSliceBinned, scaler)
				}
			}
		}
	}

	private suspend fun onSpriteClick(sprite: SpriteImage, event: MouseEvent) {

		// make sure we can edit
		if (!editable) {
			return
		}
		val particlesInfo = particlesInfos
			.firstOrNull { it.editable }
			?: return

		if (particleControls.list?.source != ParticlesSource.User) {
			return
		}

		val scaler = scaler
			?: return
		val click = event.clickRelativeTo(sprite, true)
			?: return

		// we clicked in the particles area, turn on the particle markers
		particlesInfo.checkbox.value = true

		// choose a radius for the new particle
		val radiusA = particlesInfo.newParticleRadiusA
			?: return
		val radiusBinned = radiusA.aToUnbinned(scaler)
			.unbinnedToBinned(scaler, particlesInfo.extraRadiusBinning)

		// convert mouse coords from screen space to binned voxel space
		val particle = Particle3D(
			x = click.x.normalizedToBinnedX(scaler),
			y = click.y.flipNormalized().normalizedToBinnedY(scaler),
			z = sprite.index.sliceToBinnedZ(scaler),
			r = radiusBinned
		)

		// make a new particle and save the changes on the server
		val particleId = particleControls.addParticle3D(tiltSeries.id, particle)
			?: return
		particlesInfo.particles[particleId] = particle

		// make a new marker too
		val zSliceBinned = sprite.index.sliceToBinnedZ(scaler)
		particlesInfo.makeMarker(particleId, particle, zSliceBinned, scaler)

		particlesInfo.checkbox.update(particlesInfo)

		// bubble events up to the view
		onParticlesChange?.invoke()
	}

	private suspend fun deleteParticle(particleId: Int) {

		// make sure we can edit
		if (!editable) {
			return
		}
		val particlesInfo = particlesInfos
			.firstOrNull { it.editable }
			?: return

		if (particleControls.list?.source != ParticlesSource.User) {
			return
		}

		// try to remove the particle from the server
		particleControls.removeParticle(tiltSeries.id, particleId)
			.takeIf { it }
			?: return

		// remove the particle locally
		particlesInfo.particles.remove(particleId)

		// remove the marker
		particlesInfo.markersByParticleId[particleId]
			?.let { playableSprite.sprite?.remove(it) }

		particlesInfo.checkbox.update(particlesInfo)

		// bubble events up to the view
		onParticlesChange?.invoke()
	}
}

private object Colors {
	val green = Color.hex(0x00ff00)
	val blue = Color.hex(0x0000ff)
}
