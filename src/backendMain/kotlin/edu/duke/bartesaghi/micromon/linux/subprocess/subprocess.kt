package edu.duke.bartesaghi.micromon.linux.subprocess

import edu.duke.bartesaghi.micromon.*
import java.nio.file.Path
import kotlin.io.path.div


fun socketPath(name: String): Path =
	Config.instance.web.localDir / "subprocess" / "socket-${name.toSafeFileName()}"
