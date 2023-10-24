package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.html.image


/**
 * Shows the side view image in a resizable panel.
 */
class TomoSideViewImage(
    jobId: String,
    tiltSeriesId: String
) : SizedPanel("Side View Projections", Storage.tomographyPreprocessingSideViewSize) {
    init {
        image("/kv/jobs/$jobId/data/$tiltSeriesId/sidesTiltSeriesImage",
            classes = setOf("full-width-image"))
        // set the panel resize handler
        onResize = { newSize: ImageSize ->
            // save the new size
            Storage.tomographyPreprocessingSideViewSize = newSize

        }
    }
}

class SessionTomoSideViewImage(
    sessionId: String,
    tiltSeriesId: String
) : SizedPanel("Side View Projections", Storage.tomographyPreprocessingSideViewSize) {
    init {
        image("/kv/tomographySession/$sessionId/$tiltSeriesId/sidesTiltSeriesImage",
            classes = setOf("full-width-image"))
        // set the panel resize handler
        onResize = { newSize: ImageSize ->
            // save the new size
            Storage.tomographyPreprocessingSideViewSize = newSize

        }
    }
}
