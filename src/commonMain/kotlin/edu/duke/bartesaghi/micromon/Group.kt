package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.services.ExportServiceProperty
import kotlinx.serialization.Serializable


/**
 * Represents a group of users (usually an academic lab)
 */
@Serializable
data class Group(
	/** a display name */
	val name: String,
	/** a unique identifier */
	val id: String? = null
) {

	@ExportServiceProperty(skip = true)
	val idOrThrow: String get() =
		id ?: throw NoSuchElementException("this group must have an id")
}
