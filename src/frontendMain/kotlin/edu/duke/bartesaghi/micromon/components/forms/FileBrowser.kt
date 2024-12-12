package edu.duke.bartesaghi.micromon.components.forms

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.onEvent
import io.kvision.form.text.TextInput
import io.kvision.html.*
import io.kvision.modal.Modal
import io.kvision.remote.ServiceException
import js.getHTMLElementOrThrow
import kotlinx.browser.document
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import kotlinx.html.dom.create
import kotlinx.html.js.div
import kotlinx.html.span
import org.w3c.dom.HTMLDivElement


open class FileBrowser(
	val task: Task
) {

	sealed interface Task

	data class PickFile(
		var file: String? = null,
		val filenameGlob: String? = null,
		val globTypes: Set<FileBrowserType> = DEFAULT_GLOB_TYPES
	) : Task {

		companion object {
			val DEFAULT_GLOB_TYPES = setOf(FileBrowserType.File)
		}
	}

	data class PickFolder(
		var folder: String? = null
	) : Task

	data class PickFileGlob(
		var glob: String? = null
	) : Task

	data class PickFolderGlob(
		var glob: String? = null
	) : Task

	interface FileEntryFilter {
		fun filter(type: FileBrowserType, name: String): Boolean
	}

	private abstract inner class UI(
		val win: Modal,
		val init: FileBrowserInit,
		val callback: () -> Unit
	) {

		/** a pre-defined (rather than UI-created) filter */
		open val fileEntryFilter: FileEntryFilter? = null

		// make the popup window
		val location = TextInput(classes = setOf("file-browser-location-text"))
		val content = Div(classes = setOf("file-browser-content"))
		val metadata = Div(classes = setOf("file-browser-metadata"))
		val controls = Div(classes = setOf("file-browser-controls"))

		val rootButton = Button(
			"",
			icon = "fas fa-warehouse"
		).apply {
			title = "Go to the root folder"
		}

		val homeButton = Button(
			"",
			icon = "fas fa-house-user"
		).apply {
			title = "Go to your home folder"
		}

		val upButton = Button(
			"",
			icon = "fas fa-level-up-alt",
			classes = setOf("up-button")
		).apply {
			title = "Go up to the containing folder"
		}

		val goButton = Button(
			"",
			icon = "fas fa-location-arrow",
			classes = setOf("go-button")
		).apply {
			title = "Navigate to the specified destination"
		}

		val okButton = Button(
			text = when (task) {
				is PickFile -> "Choose File"
				is PickFolder -> "Choose Folder"
				is PickFileGlob -> "Choose File Pattern"
				is PickFolderGlob -> "Choose Folder Pattern"
			}
		)

		var currentFolder: Folder? = null
			private set

		var onFolder: ((Folder) -> Unit)? = null

		init {

			// build the layout
			win.div(classes = setOf("file-browser")) {
				div(classes = setOf("file-browser-location")) {
					add(this@UI.rootButton)
					add(this@UI.homeButton)
					add(this@UI.upButton)
					add(this@UI.location)
					add(this@UI.goButton)
				}
				add(this@UI.content)
				div(classes = setOf("file-browser-bottom")) {
					add(this@UI.metadata)
					div(classes = setOf("file-browser-spacer"))
					add(this@UI.controls)
				}
			}
			win.addButton(okButton)

			// init state
			upButton.enabled = false
			okButton.enabled = false

			// wire up events
			rootButton.onClick {
				loadRoot()
			}
			homeButton.onClick {
				loadFolder(init.homeDir())
			}
			upButton.onClick {
				loadUp()
			}
			okButton.onClick {
				win.hide()
				callback()
			}
			content.onClick {
				currentFolder?.let {
					loadMetadata(it, null)
				}
				onEmptyClick(currentFolder)
			}
			location.onEnter {
				navigate()
			}
			goButton.onClick {
				navigate()
			}
		}

		fun locationPath(): String? =
			location.value
				?.trim()
				?.takeIf { it.isNotEmpty() }

		open fun navigate() {
			val path = locationPath() ?: return
			loadFolder(path)
		}

		fun loadRoot() {
			AppScope.launch {

				// build the data model for this folder
				val folder = Folder(this@UI, null)
				folder.addAll(init.dataDirs)

				show(folder)
			}
		}

		fun loadInitialFolder(path: String) {

			content.removeAll()
			currentFolder?.elem?.remove()

			// load the init info
			AppScope.launch {

				// absolutize paths
				val absPath = when {
					path.startsWith('/') -> path
					PathType.Project.matches(path) -> PathType.Project.absolutize(init.projectsDir(), path)
					else -> throw IllegalArgumentException("don't know how to absolutize path: $path")
				}

				// load the next folder
				val loading = content.loading("Loading folder ...")
				try {
					val folderInfo = Services.forms.fileBrowserFolderFast(absPath)

					// build the folder model
					val folder = Folder(this@UI, absPath)
					folder.addAll(folderInfo.entries)

					show(folder)

				} catch (ex: ServiceException) {
					content.errorMessage(ex)
					content.errorMessage("Unable to load initial folder. The folder may not exist.")
				} catch (ex: Throwable) {
					content.errorMessage(ex)
				} finally {
					content.remove(loading)
				}
			}
		}

		fun loadContainingFolder(path: String, andSelect: Boolean = false) {

			// is this a root path?
			if (init.dataDirs.names.any { it == path }) {

				// yup, load the root folder
				loadRoot(path.takeIf { andSelect })

			} else {

				// nope, split the path and load the parent
				val (parent, target) = path.popPath()
				if (parent == null) {
					content.errorMessage("invalid path: $path")
					return
				}

				loadFolder(parent, target.takeIf { andSelect })
			}
		}

		fun loadRoot(andSelect: String? = null) {

			content.removeAll()
			currentFolder?.elem?.remove()

			// yup, just show the root pseudo-folder
			val folder = Folder(this@UI, null)
			folder.addAll(init.dataDirs)

			show(folder)

			// select the target element, if needed
			if (andSelect != null) {
				folder.entries
					.find { it.name == andSelect }
					?.let { it.selected = true }
			}
		}

		fun loadFolder(path: String, andSelect: String? = null) {

			content.removeAll()
			currentFolder?.elem?.remove()

			// load the folder
			val loading = content.loading("Loading folder ...")
			AppScope.launch {
				try {

					val folderInfo = try {
						Services.forms.fileBrowserFolderFast(path)
					} catch (ex: Throwable) {
						content.errorMessage(ex)
						return@launch
					}

					// build the folder model
					val folder = Folder(this@UI, path)
					folder.addAll(folderInfo.entries)

					show(folder)

					// select the target element, if needed
					if (andSelect != null) {
						folder.entries
							.find { it.name == andSelect }
							?.let { it.selected = true }
					}

				} finally {
					content.remove(loading)
				}
			}
		}

		fun loadUp() {
			val path = currentFolder?.path ?: return
			loadContainingFolder(path)
		}

		fun load(elem: EntryElement) {

			content.removeAll()
			currentFolder?.elem?.remove()

			location.value = elem.folder.path

			// load the next folder
			val loading = content.loading("Loading folder ...")
			AppScope.launch {
				try {
					val folderInfo = Services.forms.fileBrowserFolderFast(elem.path)

					// build the folder model
					val subfolder = elem.folder.sub(elem.name)
					subfolder.addAll(folderInfo.entries)

					show(subfolder)

				} catch (ex: Throwable) {
					content.errorMessage(ex)
				} finally {
					content.remove(loading)
				}
			}
		}

		fun show(folder: Folder) {

			// clean up the old folder
			content.removeAll()
			currentFolder?.elem?.remove()

			currentFolder = folder

			location.value = folder.path ?: ""
			rootButton.enabled = folder.path != null
			homeButton.enabled = folder.path != init.homeDir()
			upButton.enabled = folder.path != null

			// show the folder element
			content.getHTMLElementOrThrow().appendChild(folder.elem)

			// show folder metadata
			loadMetadata(folder, null)

			// call the callback, if needed
			onFolder?.invoke(folder)
		}

		fun loadMetadata(folder: Folder, entry: EntryElement?) {

			metadata.removeAll()

			if (entry == null) {

				// show folder metadata
				metadata.span {
					content = "${folder.entries.size} entries in this folder"
				}

				return
			}

			AppScope.launch {
				val loading = metadata.loading()
				try {
					val file = Services.forms.fileBrowserFile(entry.path)

					// update the entry with more detailed info from the server
					entry.file = file

					// TODO: pretty formatting

					// show the file size, if possible
					val filesize = file.linkTarget?.size ?: file.size
					if (filesize != null) {
						metadata.span {
							content = "file size: ${filesize.toBytesString()}"
						}
					}

				} catch (ex: Throwable) {
					metadata.errorMessage(ex)
				} finally {
					metadata.remove(loading)
				}
			}
		}

		abstract fun launch(initialFolder: String?)
		open fun onEmptyClick(folder: Folder?) {}
		open fun onEntryClick(elem: EntryElement) {}
		open fun onEntryDblClick(elem: EntryElement) {}
	}

	private inner class FileUI(
		val task: PickFile,
		win: Modal,
		init: FileBrowserInit,
		callback: () -> Unit
	) : UI(win, init, callback) {

		override val fileEntryFilter: FileEntryFilter? = task.filenameGlob
			?.let { glob ->
				object : FileEntryFilter {

					val matcher = GlobMatcher(glob)

					override fun filter(type: FileBrowserType, name: String): Boolean =
						if (type in task.globTypes) {
							matcher.matches(name)
						} else {
							// not one of the types we care about, let it through
							true
						}
				}
			}

		override fun launch(initialFolder: String?) {

			val file = task.file
			if (file != null) {
				loadContainingFolder(file, andSelect = true)
				okButton.enabled = true
			} else if (initialFolder != null) {
				loadInitialFolder(initialFolder)
				okButton.enabled = false
			} else {
				loadRoot()
				okButton.enabled = false
			}
		}

		override fun onEmptyClick(folder: Folder?) {

			// select nothing
			win.batch {
				folder?.entries?.forEach { it.selected = false }
			}

			// update the state
			task.file = null
			okButton.enabled = false
		}

		override fun onEntryClick(elem: EntryElement) {

			// navigate into folders, but only select files
			when (elem.type) {
				FileBrowserType.Folder -> {
					load(elem)
					return
				}
				FileBrowserType.File -> Unit // allowed
				else -> return // ignore
			}

			// update the selection
			win.batch {
				elem.selected = !elem.selected
				for (e in elem.folder.entries) {
					if (e !== elem) {
						e.selected = false
					}
				}
			}

			// update the state
			if (elem.selected) {
				task.file = elem.path
				okButton.enabled = true
			} else {
				task.file = null
				okButton.enabled = false
			}
		}

		override fun onEntryDblClick(elem: EntryElement) {

			// clear the current selection
			task.file = null

			okButton.enabled = false

			when (elem.type) {
				FileBrowserType.Folder -> load(elem)
				// TODO: if file, select that file and close the popup?
				else -> Unit // ignore
			}
		}
	}

	private inner class FolderUI(
		val task: PickFolder,
		win: Modal,
		init: FileBrowserInit,
		callback: () -> Unit
	) : UI(win, init, callback) {

		override fun launch(initialFolder: String?) {

			val folder = task.folder
			if (folder != null) {
				loadContainingFolder(folder, andSelect = true)
				okButton.enabled = true
			} else if (initialFolder != null) {
				loadInitialFolder(initialFolder)
				okButton.enabled = false
			} else {
				loadRoot()
				okButton.enabled = false
			}
		}

		override fun onEmptyClick(folder: Folder?) {

			// select nothing
			task.folder = null

			// update the state
			win.batch {
				folder?.entries?.forEach { it.selected = false }
			}
			okButton.enabled = false
		}

		override fun onEntryClick(elem: EntryElement) {

			// only select folders
			if (elem.type != FileBrowserType.Folder) {
				return
			}

			// update the selection
			win.batch {
				elem.selected = !elem.selected
				for (e in elem.folder.entries) {
					if (e !== elem) {
						e.selected = false
					}
				}
			}

			// update the state
			if (elem.selected) {
				task.folder = elem.path
				okButton.enabled = true
			} else {
				task.folder = null
				okButton.enabled = false
			}
		}

		override fun onEntryDblClick(elem: EntryElement) {

			// clear the current selection
			task.folder = null

			okButton.enabled = false

			when (elem.type) {
				FileBrowserType.Folder -> load(elem)
				else -> Unit // ignore
			}
		}
	}

	private inner class FileGlobUI(
		val task: PickFileGlob,
		win: Modal,
		init: FileBrowserInit,
		callback: () -> Unit
	) : UI(win, init, callback) {

		val defaultGlob = "*"

		val countLabel = Span(classes = setOf("file-browser-count"))
		val globText = TextInput(classes = setOf("file-browser-glob")).apply {
			placeholder = defaultGlob
		}
		val button = Button(
			text = "",
			icon = "fas fa-filter"
		)

		val currentGlob: String get() =
			globText.value
				?.takeIf { it.isNotBlank() }
				?: defaultGlob

		init {

			// layout controls
			controls.apply {
				add(countLabel)
				add(globText)
				add(button)
			}

			// always enabled
			okButton.enabled = true

			// wire up events
			button.onClick {
				currentFolder?.applyGlob()
			}
			globText.onEvent {
				change = {
					// update the glob in the task
					task.glob = currentFolder?.path?.div(currentGlob)
				}
			}
			onFolder = { folder ->
				folder.applyGlob()
			}
		}

		private fun Folder.applyGlob() {
			val count = setFilesFilter(currentGlob)
			countLabel.content = when (count) {
				1 -> "1 match"
				else -> "$count matches"
			}
		}

		override fun navigate() {

			val path = locationPath() ?: return

			// look for a wildcard in the path
			val (first, last) = Paths.pop(path)
			if ('*' in last) {

				// got it, make the wildcard the current glob
				globText.value = last
				location.value = first
			}

			super.navigate()
		}

		override fun launch(initialFolder: String?) {

			// start in the glob's folder, if any
			val glob = task.glob
			if (glob != null) {
				loadContainingFolder(glob)
			} else if (initialFolder != null) {
				loadInitialFolder(initialFolder)
			} else {
				loadRoot()
			}

			globText.value = task.glob?.let { Paths.filename(it) }
		}

		override fun onEntryClick(elem: EntryElement) {

			// navigate to the folder
			when (elem.type) {
				FileBrowserType.Folder -> {
					task.glob = elem.path.div(currentGlob)
					load(elem)
				}
				else -> Unit // ignore
			}
		}
	}

	private inner class FolderGlobUI(
		val task: PickFolderGlob,
		win: Modal,
		init: FileBrowserInit,
		callback: () -> Unit
	) : UI(win, init, callback) {

		override fun launch(initialFolder: String?) {

			// start in the glob's folder, if any
			val file = task.glob
			if (file != null) {
				loadContainingFolder(file, andSelect = true)
				okButton.enabled = true
			} else if (initialFolder != null) {
				loadInitialFolder(initialFolder)
				okButton.enabled = false
			} else {
				loadRoot()
				okButton.enabled = false
			}
		}

		override fun onEntryDblClick(elem: EntryElement) {

			// navigate to the folder
			when (elem.type) {
				FileBrowserType.Folder -> load(elem)
				else -> Unit // ignore
			}
		}
	}

	companion object {

		private const val iconFile = "fas fa-file"
		private const val iconFolder = "fas fa-folder"
		private const val iconSymlink = "fas fa-external-link-square-alt"
		private const val iconBrokenSymlink = "fas fa-unlink"
		private const val iconOther = "fas fa-question-circle"
	}


	fun launch(initialFolder: String? = null, callback: () -> Unit) {

		val win = Modal(
			caption = when (task) {
				is PickFile -> "Choose a file"
				is PickFolder -> "Choose a folder"
				is PickFileGlob -> "Choose a file pattern"
				is PickFolderGlob -> "Choose a folder pattern"
			},
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup", "full-height-dialog", "full-width-dialog")
		)
		win.show()

		AppScope.launch l@{

			val loading = win.loading("Querying filesystems ...")
			val init = try {
				Services.forms.fileBrowserInit()
			} catch (ex: Throwable) {
				win.errorMessage(ex)
				return@l
			} finally {
				win.remove(loading)
			}

			val ui = when (task) {
				is PickFile -> FileUI(task, win, init, callback)
				is PickFolder -> FolderUI(task, win, init, callback)
				is PickFileGlob -> FileGlobUI(task, win, init, callback)
				is PickFolderGlob -> FolderGlobUI(task, win, init, callback)
			}
			ui.launch(initialFolder)
		}
	}

	private class Folder(
		val ui: UI,
		val path: String?
	) {

		private val _elems = ArrayList<EntryElement>()
		val entries: List<EntryElement> get() = _elems

		val elem = document.create.div(classes = "file-browser-folder")

		fun addAll(entries: FileBrowserEntries) {

			for (i in 0 until entries.size) {

				val name = entries.names[i]
				val type = entries.type(i)

				// apply the file entry filter, if any
				val filter = ui.fileEntryFilter
				if (filter != null && !filter.filter(type, name)) {
					continue
				}

				// create the entry
				val entry = EntryElement(this, name, FileBrowserFile(type))
				_elems.add(entry)

				// add it to the folder
				// but wrap the element in an extra div, to absorb the layout sizing from the flexbox container
				elem.appendChild(document.create.div().apply {
					appendChild(entry.elem)
				})

				entry.elem.onclick = {
					// handle the event here, but don't let it bubble up to the empty click handler too
					it.stopPropagation()
					ui.onEntryClick(entry)
					ui.loadMetadata(this, entry)
				}
				entry.elem.ondblclick = {
					// handle the event here, but don't let it bubble up to the regular click handler too
					it.stopPropagation()
					ui.onEntryDblClick(entry)
				}
			}
		}

		fun sub(name: String): Folder =
			Folder(
				ui,
				path
					?.let { it / name }
					?: name
			)

		fun setFilesFilter(glob: String?): Int? {

			// if no filter, show everything
			if (glob == null) {
				for (entry in entries) {
					entry.visible = true
				}
				return null
			}

			var count = 0

			// apply the filter
			val matcher = GlobMatcher(glob)
			for (entry in entries) {
				when (entry.type) {

					// folders are always visible
					FileBrowserType.Folder -> entry.visible = true

					// filter everything else
					else -> {
						entry.visible = matcher.matches(entry.name)
						if (entry.visible) {
							count += 1
						}
					}
				}
			}

			return count
		}
	}

	private class EntryElement(
		val folder: Folder,
		val name: String,
		file: FileBrowserFile
	) {

		// NOTE: snabbdom is WAAAAY too slow to handle thousands of file entries in less than a few seconds
		// so fall back to good ol' fast and reliable raw DOM
		// seriously, raw DOM is like 10x faster than snabbdom, according to real-world benchmarks on this file browser code
		// personally, I think virtual DOMs are dumb... =P the marketing gloss pretends they're faster, but they're just not
		// the tree sync/diff overhead is real!
		val elem: HTMLDivElement =
			document.create.div(classes = "file-browser-entry")

		var visible: Boolean
			get() = !elem.hidden
			set(value) { elem.hidden = !value }

		var file: FileBrowserFile = file
			set (value) {
				field = value
				update()
			}

		// lay out the display elements
		// use raw HTML here, for speed
		private val elemIcon = document.create.span()
		private val elemText = document.create.span()
		init {
			elem.append(elemIcon, elemText)
			update()
		}

		private fun update() {
			// NOTE: don't delete/replace the HTML elements here
			// that will mess up double-click detection
			// just edit the existing HTML elements in-place
			elemIcon.className = "icon " + when (type) {
				FileBrowserType.Folder -> iconFolder
				FileBrowserType.File -> iconFile
				FileBrowserType.Symlink -> iconSymlink
				FileBrowserType.BrokenSymlink -> iconBrokenSymlink
				FileBrowserType.Other -> iconOther
			}
			elemText.textContent = name
		}

		val type: FileBrowserType get() =
			file.linkTarget?.type ?: file.type

		var selected: Boolean = false
			set(value) {
				field = value
				"file-browser-entry-selected".let {
					if (value) {
						elem.addClass(it)
					} else {
						elem.removeClass(it)
					}
				}
			}

		/** the absolute path of this entry */
		val path: String get() =
			folder.path
				?.let { it / name }
				?: name
	}
}
