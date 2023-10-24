package edu.duke.bartesaghi.micromon.jobs


/**
 * A consistent way to pack/unpack a (project,job) tuple into an owner id
 */
class JobOwner(
	val jobId: String,
	val runId: Int
) {

	override fun toString() = "$jobId/$runId"

	companion object {

		fun fromString(owner: String?): JobOwner? {
			if (owner == null) {
				return null
			}
			val parts = owner.split("/")
			return if (parts.size == 2) {
				JobOwner(
					parts[0],
					parts[1].toIntOrNull() ?: return null
				)
			} else {
				null
			}
		}
	}
}
