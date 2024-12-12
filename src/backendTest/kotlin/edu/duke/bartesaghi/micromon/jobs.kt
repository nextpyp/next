package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.jobs.Job
import edu.duke.bartesaghi.micromon.services.JobData


fun JobData.job(): Job =
	Job.fromIdOrThrow(jobId)
