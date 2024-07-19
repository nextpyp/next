package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.components.forms.enableClickIf
import edu.duke.bartesaghi.micromon.components.forms.enabled
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.form.check.radioGroup
import io.kvision.modal.Modal
import io.kvision.toast.ToastMethod
import io.kvision.toast.ToastOptions
import io.kvision.toast.ToastPosition
import io.kvision.core.StringPair
import io.kvision.form.text.text
import io.kvision.html.*
import io.kvision.modal.Confirm
import io.kvision.toast.Toast


interface ParticleControls {

	val list: ParticlesList?
	var onListChange: (() -> Unit)?
	val canWrite: Boolean
	val numParticles: Long

	suspend fun addParticle2D(micrographId: String, particle: Particle2D): Int? = null
	suspend fun addParticle3D(micrographId: String, particle: Particle3D): Int? = null
	suspend fun removeParticle(micrographId: String, particleId: Int): Boolean = false
}


class MultiListParticleControls(
	val project: ProjectData,
	val job: JobData,
	var newParticlesType: ParticlesType? = null,
	initialList: ParticlesList? = null
): Div(), ParticleControls {

	override var list: ParticlesList? = initialList
		private set

	override var numParticles: Long = 0
		private set

	init {
		// load the initial particle count, if needed
		if (list != null) {
			AppScope.launch {
				updateCount()
			}
		}
	}

	override var onListChange: (() -> Unit)? = null

	override val canWrite get() =
		project.canWrite()

	private val listNameText = Span(classes = setOf("name"))

    private val newButton = Button("New", icon = "fas fa-cloud-upload-alt").apply {
		title = "Create a new list of particles"
        enableClickIf(project.canWrite()) {
            newList()
        }
    }

    private val deleteButton = Button("Delete", icon = "fas fa-trash").apply {
		title = "Delete the current list of particles"
		enableClickIf(project.canWrite()) {
            deleteList()
        }
    }

	private val copyButton = Button("Copy", icon = "fas fa-copy").apply {
		title = "Copy the current list of particles to a new list"
		enableClickIf(project.canWrite()) {
			copyList()
		}
	}

    private val loadButton = Button("Load", icon = "fas fa-cloud-download-alt").apply {
		title = "Show another list of particles"
        onClick {
            showLists()
        }
    }


	init {

		// DSLs are annoying when they won't let you access the outer scope
		val self = this

        div(classes = setOf("particle-controls")) {
			span("Particles List:", classes = setOf("label"))
            add(self.listNameText)
            add(self.newButton)
			add(self.copyButton)
            add(self.deleteButton)
            add(self.loadButton)
        }

		update()
    }

	private fun update() {

		listNameText.content = list?.name
			?: "(None)"

		// update buttons
		val hasList = list != null
		deleteButton.enabled = hasList
		copyButton.enabled = hasList
	}

    private fun showLists() {

        // show a popup with the particle picking options
        val popup = Modal(
			caption = "Load particles list",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup")
        )

        val loadButton = Button("Load")
			.apply {
				enabled = false
			}

        popup.addButton(loadButton)

        popup.show()

        AppScope.launch {

            val loading = popup.loading("Loading lists ...")
            try {
                val lists = Services.particles.getLists(OwnerType.Project, job.jobId)

                val radio = popup.radioGroup(
					options = lists.map { StringPair(it.name, it.name) },
					value = list?.name
                )

                loadButton.enabled = true
                loadButton.onClick {

					val name = radio.value
						?: return@onClick

					popup.hide()

					val list = lists
						.find { it.name == name }

					AppScope.launch {
						setList(list)
					}
                }

            } catch (t: Throwable) {
                popup.errorMessage(t)
            } finally {
                popup.remove(loading)
            }
        }
    }
    private val toastOptions = ToastOptions(
		positionClass = ToastPosition.TOPLEFT,
		showMethod = ToastMethod.SLIDEDOWN,
		hideMethod = ToastMethod.SLIDEUP
    )

	suspend fun setList(list: ParticlesList?) {
		this.list = list
		update()
		updateCount()
		onListChange?.invoke()
	}

	private suspend fun updateCount() {

		numParticles = 0

		val list = list
			?: return

		numParticles =
			try {
				Services.particles.countParticles(OwnerType.Project, job.jobId, list.name, null)
					.unwrap()
			} catch (t: Throwable) {
				null
			}
			?: 0
	}

	override suspend fun addParticle2D(micrographId: String, particle: Particle2D): Int? {

		val list = list
			?: return null

		return try {
			Services.particles.addParticle2D(OwnerType.Project, job.jobId, list.name, micrographId, particle)
				.also { numParticles += 1 }
		} catch (t: Throwable) {
			Toast.error(t.message ?: "(unknown reason)",  "Failed to save particle addition", options = toastOptions)
			null
		}
	}

	override suspend fun addParticle3D(micrographId: String, particle: Particle3D): Int? {

		val list = list
			?: return null

		return try {
			val particleId = Services.particles.addParticle3D(OwnerType.Project, job.jobId, list.name, micrographId, particle)
			numParticles += 1
			particleId
		} catch (t: Throwable) {
			Toast.error(t.message ?: "(unknown reason)",  "Failed to save particle addition", options = toastOptions)
			null
		}
	}

	override suspend fun removeParticle(micrographId: String, particleId: Int): Boolean {

		val list = list
			?: return false

		return try {
			Services.particles.deleteParticle(OwnerType.Project, job.jobId, list.name, micrographId, particleId)
			numParticles -= 1
			true
		} catch (t: Throwable) {
			Toast.error(t.message ?: "(unknown reason)",  "Failed to save particle removal", options = toastOptions)
			false
		}
	}

	private fun newList() {

		val newParticlesType = newParticlesType
			?: throw IllegalStateException("please set a new particles type before creating a new list")

		// ask for a list name in a popup
		val popup = Modal(
			caption = "New particles list",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup")
		)

		popup.p("Please choose a name for your new list")
		val nameText = popup.text(label = "Name:")

		val createButton = Button("Create")
		popup.addButton(createButton)
		createButton.onClick {

			// get the list name
			val name = nameText.value ?: ""
			if (name.isBlank()) {
				Toast.error("Please choose a name for the new particle list.", options = ToastOptions())
				return@onClick
			}

			AppScope.launch {

				val list: ParticlesList? = try {
					Services.particles.addList(OwnerType.Project, job.jobId, name, newParticlesType)
						.unwrap()
				} catch (t: Throwable) {
					Toast.error(t.message ?: "(unknown reason)",  "List save failed", options = toastOptions)
					return@launch
				}

				if (list == null) {
					Toast.error("That name is already taken. Please choose another one.", options = ToastOptions())
					return@launch
				}

				popup.hide()
				Toast.success("List saved!", options = toastOptions)
				setList(list)
			}
		}

		popup.show()
	}

	private fun deleteList() {

		// get the selection, if any
		val list = list
		if (list == null) {
			Toast.error("Please load a list to delete.", options = ToastOptions())
			return
		}

		Confirm.show(text = "Really delete the list: ${list.name}? This can't be undone.") {

			AppScope.launch {
				val deleted = try {
					Services.particles.deleteList(OwnerType.Project, job.jobId, list.name)
				} catch (t: Throwable) {
					Toast.error(t.message ?: "(unknown reason)",  "List deletion failed", options = toastOptions)
					return@launch
				}

				if (!deleted) {
					Toast.error("Unable to delete list.", options = ToastOptions())
					return@launch
				}

				Toast.success("List deleted!", options = toastOptions)
				setList(null)
			}
		}
	}

	private fun copyList() {

		// get the selection, if any
		val list = list
		if (list == null) {
			Toast.error("Please load a list to copy.", options = ToastOptions())
			return
		}

		// ask for a list name in a popup
		val popup = Modal(
			caption = "Copy particles list",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup")
		)

		popup.div("Copying list: ${list.name}")
		popup.p("Please choose a name for your new particle list.")
		val nameText = popup.text(label = "Name:")

		val copyButton = Button("Copy")
		popup.addButton(copyButton)
		copyButton.onClick {

			// get the list name
			val newName = nameText.value ?: ""
			if (newName.isBlank()) {
				Toast.error("Please choose a name for the new particle list.", options = ToastOptions())
				return@onClick
			}

			AppScope.launch {

				val newList: ParticlesList? = try {
					Services.particles.copyList(OwnerType.Project, job.jobId, list.name, newName)
						.unwrap()
				} catch (t: Throwable) {
					Toast.error(t.message ?: "(unknown reason)",  "List copy failed", options = toastOptions)
					return@launch
				}

				if (newList == null) {
					Toast.error("That name is already taken. Please choose another one.", options = ToastOptions())
					return@launch
				}

				popup.hide()
				Toast.success("List copied!", options = toastOptions)
				setList(newList)
			}
		}

		popup.show()
	}
}


class SingleListParticleControls(
	val project: ProjectData,
	val job: JobData,
): Div(), ParticleControls {

	override var list: ParticlesList? = null

	override var numParticles: Long = 0
		private set

	init {
		// load the initial particle count, if needed
		AppScope.launch {
			updateCount()
		}
	}

	override var onListChange: (() -> Unit)? = null

	override val canWrite get() =
		project.canWrite()

	suspend fun load(default: ParticlesList) {

		this.list = Services.particles.getLists(OwnerType.Project, job.jobId)
			.find { it.name == default.name }
			?: run {
				Services.particles.addList(OwnerType.Project, default.ownerId, default.name, default.type)
				default
			}

		updateCount()
		onListChange?.invoke()
	}

	private val toastOptions = ToastOptions(
		positionClass = ToastPosition.TOPLEFT,
		showMethod = ToastMethod.SLIDEDOWN,
		hideMethod = ToastMethod.SLIDEUP
	)

	private suspend fun updateCount() {

		numParticles = 0

		val list = list
			?: return

		numParticles =
			try {
				Services.particles.countParticles(OwnerType.Project, job.jobId, list.name, null)
					.unwrap()
			} catch (t: Throwable) {
				null
			}
			?: 0
	}

	override suspend fun addParticle2D(micrographId: String, particle: Particle2D): Int? {

		val list = list
			?: return null

		return try {
			Services.particles.addParticle2D(OwnerType.Project, job.jobId, list.name, micrographId, particle)
				.also { numParticles += 1 }
		} catch (t: Throwable) {
			Toast.error(t.message ?: "(unknown reason)",  "Failed to save particle addition", options = toastOptions)
			null
		}
	}

	override suspend fun addParticle3D(micrographId: String, particle: Particle3D): Int? {

		val list = list
			?: return null

		return try {
			val particleId = Services.particles.addParticle3D(OwnerType.Project, job.jobId, list.name, micrographId, particle)
			numParticles += 1
			particleId
		} catch (t: Throwable) {
			Toast.error(t.message ?: "(unknown reason)",  "Failed to save particle addition", options = toastOptions)
			null
		}
	}

	override suspend fun removeParticle(micrographId: String, particleId: Int): Boolean {

		val list = list
			?: return false

		return try {
			Services.particles.deleteParticle(OwnerType.Project, job.jobId, list.name, micrographId, particleId)
			numParticles -= 1
			true
		} catch (t: Throwable) {
			Toast.error(t.message ?: "(unknown reason)",  "Failed to save particle removal", options = toastOptions)
			false
		}
	}
}


/**
 * Only shows one particle list, but doesn't show any controls or allow particle picking
 */
class ShowListParticleControls(override val list: ParticlesList) : ParticleControls {
	override var onListChange: (() -> Unit)? = null
	override val canWrite get() = false
	override val numParticles get() = 0L
}


class NoneParticleControls : ParticleControls {
	override val list: ParticlesList? = null
	override var onListChange: (() -> Unit)? = null
	override val canWrite: Boolean = false
	override val numParticles get() = 0L
}
