package edu.duke.bartesaghi.micromon


/**
 * Some js libraries return false instead of null,
 * but the Kotlin type wrappers can't really handle that.
 * So look for false values and change them to null.
 */
fun <T:Any> T?.falseToNull(): T? {
	if (asDynamic() === false) {
		return null
	}
	return this
}


operator fun Function<*>.invoke() {
	// don't know why this isn't provided by the stdlib ...
	this.asDynamic()()
}


fun Throwable.reportError(msg: String? = null) {

	data class FrameType(
		val name: String,
		val care: Boolean,
		val filenames: List<String>
	)

	val frameTypes = mutableListOf<FrameType>()
	fun addFrameType(frameType: FrameType): FrameType =
		frameType.also { frameTypes.add(it) }

	addFrameType(FrameType("Kotlin", false, listOf(
		"kotlin.js"
	)))
	addFrameType(FrameType("Micromon", true, listOf(
		"micromon-frontend.js"
	)))
	val frameTypeCoroutines = addFrameType(FrameType("Coroutines", false, listOf(
		"kotlinx-coroutines-core.js"
	)))
	addFrameType(FrameType("KVision", true, listOf(
		"kvision-kvision-common-remote-js-legacy.js",
		"kvision-js-legacy.js"
	)))
	addFrameType(FrameType("Navigo", false, listOf(
		"navigo.min.js"
	)))
	addFrameType(FrameType("Snabbdom", false, listOf(
		"snabbdom.js",
		"eventlisteners.js"
	)))
	addFrameType(FrameType("JQuery", false, listOf(
		"jquery.js"
	)))
	addFrameType(FrameType("Serialization", false, listOf(
		"kotlinx-serialization-kotlinx-serialization-core-js-legacy.js",
		"kotlinx-serialization-kotlinx-serialization-json-js-legacy.js"
	)))

	val maxNameLen = frameTypes.maxOf { it.name.length }

	data class Frame(
		val raw: String,
		val name: String?,
		val path: String?,
		val filename: String?,
		val line: Int?,
		val char: Int?,
		val type: FrameType?
	) {
		fun care(): Boolean =
			type == null || type.care
	}

	fun parseFrame(line: String): Frame {

		// lines look like, eg:
		// RuntimeException_init_0@webpack://micromon/./kotlin-dce-dev/kotlin.js?:38962:24
		// HyperGallery.prototype.load@webpack://micromon/./kotlin-dce-dev/micromon-frontend.js?:55202:49
		// WindowDispatcher.prototype.dispatch_5bn72i$@webpack://micromon/./kotlin-dce-dev/kotlinx-coroutines-core.js?:32157:16
		// Socket.prototype.onWsEvent_0@webpack://micromon/./kotlin-dce-dev/kvision-kvision-common-remote-js-legacy.js?:6255:11
		// Widget.prototype.getRoot@webpack://micromon/./kotlin-dce-dev/kvision-js-legacy.js?:8523:39)
		// n@webpack://micromon/../../node_modules/navigo/lib/navigo.min.js?:1:2069

		val parts = line.split('@')
		val name = parts.getOrNull(0)
		val parts2 = parts.getOrNull(1)?.split('?')
		val path = parts2?.getOrNull(0)
		val filename = path?.split('/')?.lastOrNull()
		val posParts = parts2?.getOrNull(1)?.split(':')
		val linenum = posParts?.getOrNull(1)?.toInt()
		val char = posParts?.getOrNull(2)?.toInt()

		// classify the stack frame
		var type = frameTypes.firstOrNull { filename in it.filenames }

		// HACKHACK: `doResume` calls are still technically part of the coroutines system, but they still show up in other files
		if (name?.endsWith(".doResume") == true ) {
			type = frameTypeCoroutines
		}

		return Frame(line, name, path, filename, linenum, char, type)
	}

	fun formatStack(stack: String?): String {

		if (stack == null) {
			return "(no stack trace was available)"
		}

		// parse the stack trace into frames
		val frames = stack.lineSequence()
			.filter { it.isNotBlank() }
			.map(::parseFrame)

		// collapse frames we don't care about
		val frameGroups = frames
			.map { mutableListOf(it) }
			.toMutableList()
		var i = 0
		while (i < frameGroups.size) {
			val group = frameGroups[i]
			val nexti = i + 1
			if (group[0].care()) {
				// we care about this group, skip it
				i += 1
			} else if (nexti < frameGroups.size) {
				val nextGroup = frameGroups[nexti]

				// try to collapse the next frame into this one
				if (!nextGroup[0].care()) {
					group.addAll(nextGroup)
					frameGroups.removeAt(nexti)
				} else {
					// we care about the next group, so skip this one and the next one both
					i += 2
				}
			} else {
				i += 1
			}
		}

		return frameGroups.joinToString("") { frameGroup ->
			"\n" + if (frameGroup.size == 1) {
				val frame = frameGroup[0]
				if (frame.type == null) {
					"${"".padEnd(maxNameLen)} (unrecognized stack frame: ${frame.raw})"
				} else if (frame.type.care) {
					"${frame.type.name.padEnd(maxNameLen)} ${frame.name}"
				} else {
					"${"".padEnd(maxNameLen)}     (omitted frame: ${frame.type.name})"
				}
			} else {
				val counts = HashMap<FrameType?,Int>()
				for (frame in frameGroup) {
					counts[frame.type] = 1 + (counts[frame.type] ?: 0)
				}
				val countsDesc = counts.entries.joinToString(", ") { (type, count) ->
					"${type?.name ?: "(unknown)"}: $count"
				}
				"${"".padEnd(maxNameLen)}     (omitted frames: $countsDesc)"
			}
		}
	}

	// parse the javascript stack trace
	val stack = formatStack(this.asDynamic().stack as String?)

	if (msg != null) {
		console.error("$msg:", toString(), stack)
	} else {
		console.error(toString(), stack)
	}

	// report causes too
	cause?.reportError("Caused By")
}
