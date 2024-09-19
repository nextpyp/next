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
import kotlin.reflect.KMutableProperty0


/**
 * Shows the Tilt Series Images and a checkbox to show/hide particles.
 * Provides functionality to add/delete markers.
 */
class TomoParticlesImage(
	val tiltSerieses: TiltSeriesesData,
	val tiltSeries: TiltSeriesData,
	val particleControls: ParticleControls?,
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
			particleControls: ParticleControls? = null
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
				if (tiltSerieses.particles is TiltSeriesesParticlesData.VirusMode) {
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

	var onParticlesChange: (() -> Unit)? = null

	private class ParticlesInfo(
		val list: ParticlesList,
		val color: Color,
		val checkbox: ParticlesCheckBox,
		val editable: Boolean,
		/** particles are in unbinned coordinates */
		val particles: MutableMap<Int,Particle3D>,
		val newParticleRadius: ValueA?
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

	private val scaleBar = ScaleBar(tiltSeries.imageDims)
	private var measureTool: MeasureTool.ActivateButton? = null

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
		particleControls?.onListChange = {
			AppScope.launch {

				loadParticlesAsync()
				updateParticlesCheckboxes()
				updateParticles()

				// bubble events up to the view
				onParticlesChange?.invoke()
			}
		}

        add(playableSprite)

		// add the checkboxes, if needed
		if (particleControls != null) {
			val particles = tiltSerieses.particles
			if (particles is TiltSeriesesParticlesData.VirusMode) {
				add(showVirionsCheck)
				if (particles.spikes != null) {
					add(showSpikesCheck)
				}
			} else {
				add(showParticlesCheck)
			}
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
		val numSlices = tiltSeries.imageDims.numSlices
		playableSprite.load(numSlices, numSlices/2 - 1) { sprite ->

			// show the scale bar
			sprite.add(scaleBar)

			// add the measure tool
			// cleanup any previous measure buttons
			playableSprite.rightDiv.getChildren()
				.filter { MeasureTool.isButton(it) }
				.forEach { remove(it) }
			// add the new one
			val measureTool = MeasureTool.ActivateButton(sprite, tiltSeries.imageDims, MeasureTool.showIn(scaleBar))
			playableSprite.rightDiv.add(0, measureTool)
			this.measureTool = measureTool

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

		suspend fun getParticles(name: String) =
			Services.particles.getParticles3D(ownerType, ownerId, name, tiltSeries.id)
				.toMap()
				.toMutableMap()

		suspend fun TiltSeriesesParticlesData.Data.addInfo(
			checkbox: ParticlesCheckBox,
			editable: Boolean,
			overrideList: ParticlesList? = null
		) {

			val list = overrideList ?: this.list
				?: return

			particlesInfos.add(ParticlesInfo(
				list = list,
				color = when (list.type) {
					ParticlesType.Virions3D -> Colors.blue
					else -> Colors.green
				},
				checkbox = checkbox,
				editable = editable,
				particles = getParticles(list.name),
				newParticleRadius = this.radius
			))
		}

		when (val particles = tiltSerieses.particles) {

			null -> {
				// the block itself has no main particles list,
				// but the user may have created a manual list,
				// so show that here
				TiltSeriesesParticlesData.Data(
					list = ParticlesList.manualParticles3D(ownerId),
					radius = tiltSerieses.finishedValues?.tomoSpkRadOrDefault
						?: run {
							console.warn("No particle radius defined in pyp arg values, using arbitrary value")
							ValueA(100.0)
						}
				).addInfo(
					checkbox = showParticlesCheck,
					editable = true,
					overrideList = particleControls?.list
				)
			}

			is TiltSeriesesParticlesData.VirusMode -> {

				// in virus mode, the picked particles are virions
				particles.virions.addInfo(
					checkbox = showVirionsCheck,
					editable = true,
					overrideList = particleControls?.list
				)

				// if there are any auto particles, show those too as spikes, but don't allow editing
				particles.spikes?.addInfo(
					checkbox = showSpikesCheck,
					editable = false
				)
			}

			// in regular mode, use the picked particles, if any
			is TiltSeriesesParticlesData.Data -> {
				particles.addInfo(
					checkbox = showParticlesCheck,
					editable = true,
					overrideList = particleControls?.list
				)
			}
		}
	}

	private fun ParticlesInfo.makeMarker(particleId: Int, particle: Particle3D, zSlice: ValueUnbinnedI) {

		val r = particle.r
		val x = particle.x
		val y = particle.y
		val z = particle.z

		// slice the particle sphere at the z-coordinate of the tomogram slice to get the reduced radius
		val dz = (z - zSlice).abs().toF()
		val square = r*r - dz*dz
		if (square < ValueUnbinnedF(0.0)) {
			return
		}
		val sliceRadius = square.sqrt()

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
			x.toF().toNormalizedX(tiltSeries.imageDims),
			y.toF().toNormalizedY(tiltSeries.imageDims),
			sliceRadius.toNormalizedX(tiltSeries.imageDims),
			sliceRadius.toNormalizedY(tiltSeries.imageDims)
		)
	}

	private fun ParticlesInfo.createMarkersInRange(zSlice: ValueUnbinnedI) {

		// make markers for all coords within the z-range of the slice
		for ((particleId, particle) in particles) {

			val r = particle.r
			val z = particle.z

			// skip coords that are out of range
			val dz = (z - zSlice).toF()
			if (dz*dz > r*r) {
				continue
			}

			makeMarker(particleId, particle, zSlice)
		}
	}

	private fun updateParticlesCheckboxes() {

		if (tiltSerieses.particles is TiltSeriesesParticlesData.VirusMode) {

			val virionsInfo = particlesInfos
				.find { it.list.type == ParticlesType.Virions3D }
			val spikesInfo = particlesInfos
				.find { it.list.type == ParticlesType.Particles3D }

			showVirionsCheck.update(virionsInfo)
			showSpikesCheck.update(spikesInfo)
			showParticlesCheck.update(null)

		} else {

			val particlesInfo = particlesInfos
				.firstOrNull()

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

		// get the z-coordinate of the current slice, in unbinned voxels
		val zSlice = sprite.index.sliceToUnbinnedZ(tiltSeries.imageDims)

		// create markers for all the particles in range of the z slice
		batch {
			for (info in particlesInfos) {
				if (info.checkbox.value) {
					info.createMarkersInRange(zSlice)
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

		// ignore clicks in measure tool mode
		if (measureTool?.isActive == true) {
			return
		}

		if (particleControls?.list?.source != ParticlesSource.User) {
			return
		}

		val click = event.clickRelativeTo(sprite, true)
			?: return

		// we clicked in the particles area, turn on the particle markers
		particlesInfo.checkbox.value = true

		// choose a radius for the new particle
		val radius = particlesInfo.newParticleRadius
			?: return

		val particle = Particle3D(
			x = click.x
				.normalizedToUnbinnedX(tiltSeries.imageDims)
				.toI(),
			y = click.y
				.flipNormalized()
				.normalizedToUnbinnedY(tiltSeries.imageDims)
				.toI(),
			z = sprite.index
				.sliceToUnbinnedZ(tiltSeries.imageDims),
			r = radius
				.toUnbinned(tiltSeries.imageDims)
		)

		// make a new particle and save the changes on the server
		val particleId = particleControls.addParticle3D(tiltSeries.id, particle)
			?: return
		particlesInfo.particles[particleId] = particle

		// make a new marker too
		val zSlice = sprite.index.sliceToUnbinnedZ(tiltSeries.imageDims)
		particlesInfo.makeMarker(particleId, particle, zSlice)

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

		if (particleControls?.list?.source != ParticlesSource.User) {
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
