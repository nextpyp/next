package edu.duke.bartesaghi.micromon.views.admin

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.services.ServerVal
import edu.duke.bartesaghi.micromon.services.Services


object Admin {

	/**
	 * A client-side cache of the admin info.
	 * Make sure to clear this when logging out or it will become stale
	 */
	val info = ServerVal {
		Services.admin.getInfo()
	}

	// eagerly populate the admin info
	init {
		AppScope.launch {
			info.get()
		}
	}
}
