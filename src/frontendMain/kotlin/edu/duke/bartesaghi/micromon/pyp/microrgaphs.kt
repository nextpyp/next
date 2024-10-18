package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.components.indexSearch
import edu.duke.bartesaghi.micromon.services.MicrographMetadata


fun List<MicrographMetadata>.searchById(q: String) =
	indexSearch { m -> m.id.takeIf { q in m.id } }
