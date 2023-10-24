package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.jobs.Job
import java.nio.file.Path
import kotlin.io.path.div


class GainCorrectedImage {

	companion object {

		fun path(job: Job): Path =
			job.dir / "gain_corrected.webp"
	}
}
