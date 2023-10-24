package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.baseName
import edu.duke.bartesaghi.micromon.ctime
import edu.duke.bartesaghi.micromon.readString
import java.nio.file.Path
import java.time.Instant


class LogInfo(val id: String, val path: Path) {

	/** will be null if the file does not exist */
	val timestamp: Instant? =
		path.ctime()

	val type: String? get() {
		val name = path.fileName.baseName()
		return if (name.startsWith(id + '_')) {
			name.substring(id.length + 1)
		} else {
			null
		}
	}

	fun read(): String =
		path.readString()
}
