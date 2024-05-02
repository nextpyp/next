package edu.duke.bartesaghi.micromon.linux.subprocess

import edu.duke.bartesaghi.micromon.*
import java.nio.file.Path


fun socketPath(dir: Path, name: String): Path =
	dir.resolve("/tmp/nextpyp-subprocess/socket-${name.toSafeFileName()}")
