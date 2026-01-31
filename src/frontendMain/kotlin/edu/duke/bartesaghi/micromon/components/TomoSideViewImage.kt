package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.pyp.ImageDims
import edu.duke.bartesaghi.micromon.services.*
import io.kvision.core.Position
import io.kvision.html.Image
import io.kvision.html.div
import io.kvision.html.image


/**
 * Shows the side view image in a resizable panel.
 * When [imageDims] is provided, shows a scale bar and measure tool.
 */
class TomoSideViewImage(
    jobId: String,
    tiltSeriesId: String,
    imageDims: ImageDims? = null
) : SizedPanel("Side View Projections", Storage.tomographyPreprocessingSideViewSize) {
    init {
        addSideViewContent(
            "/kv/jobs/$jobId/data/$tiltSeriesId/sidesTiltSeriesImage",
            imageDims
        )
        onResize = { newSize: ImageSize ->
            // save the new size
            Storage.tomographyPreprocessingSideViewSize = newSize

        }
    }
}

class TomoSideViewDenoiseImage(
    jobId: String,
    tiltSeriesId: String,
    imageDims: ImageDims? = null
) : SizedPanel("Side View Projections", Storage.tomographyPreprocessingSideViewSize) {
    init {
        addSideViewContent(
            "/kv/jobs/$jobId/data/$tiltSeriesId/sidesTiltSeriesDenoiseImage",
            imageDims
        )
        onResize = { newSize: ImageSize ->
            // save the new size
            Storage.tomographyPreprocessingSideViewSize = newSize

        }
    }
}

class TomoSideViewSegImage(
    jobId: String,
    tiltSeriesId: String,
    imageDims: ImageDims? = null
) : SizedPanel("Side View Projections", Storage.tomographyPreprocessingSideViewSize) {
    init {
        addSideViewContent(
            "/kv/jobs/$jobId/data/$tiltSeriesId/sidesTiltSeriesSegmentImage",
            imageDims
        )
        onResize = { newSize: ImageSize ->
            // save the new size
            Storage.tomographyPreprocessingSideViewSize = newSize

        }
    }
}

class SessionTomoSideViewImage(
    sessionId: String,
    tiltSeriesId: String,
    imageDims: ImageDims? = null
) : SizedPanel("Side View Projections", Storage.tomographyPreprocessingSideViewSize) {
    init {
        addSideViewContent(
            "/kv/tomographySession/$sessionId/$tiltSeriesId/sidesTiltSeriesImage",
            imageDims
        )
        onResize = { newSize: ImageSize ->
            // save the new size
            Storage.tomographyPreprocessingSideViewSize = newSize

        }
    }
}

class SessionTomoSideViewDenoiseImage(
    sessionId: String,
    tiltSeriesId: String,
    imageDims: ImageDims? = null
) : SizedPanel("Side View Projections", Storage.tomographyPreprocessingSideViewSize) {
    init {
        addSideViewContent(
            "/kv/tomographySession/$sessionId/$tiltSeriesId/sidesTiltSeriesDenoiseImage",
            imageDims,
            usePlaceholderOnError = true
        )
        onResize = { newSize: ImageSize ->
            // save the new size
            Storage.tomographyPreprocessingSideViewSize = newSize

        }
    }
}

class SessionTomoSideViewSegImage(
    sessionId: String,
    tiltSeriesId: String,
    imageDims: ImageDims? = null
) : SizedPanel("Side View Projections", Storage.tomographyPreprocessingSideViewSize) {
    init {
        addSideViewContent(
            "/kv/tomographySession/$sessionId/$tiltSeriesId/sidesTiltSeriesSegImage",
            imageDims,
            usePlaceholderOnError = true
        )
        onResize = { newSize: ImageSize ->
            Storage.tomographyPreprocessingSideViewSize = newSize
        }
    }
}

/**
 * Adds the side view image to the panel. When [imageDims] is non-null,
 * wraps the image in a positioned container and adds a scale bar and measure tool.
 * When [usePlaceholderOnError] is true, shows a placeholder image if the image fails to load.
 */
private fun SizedPanel.addSideViewContent(imageUrl: String, imageDims: ImageDims?, usePlaceholderOnError: Boolean = false) {
    if (imageDims != null) {
        val imageElem = Image("", classes = setOf("full-width-image"))
        imageElem.src = imageUrl
        if (usePlaceholderOnError) imageElem.ifNonExistentUsePlaceholder(size)
        val scaleBar = ScaleBar(imageDims)
        val imageContainerElem = div {
            position = Position.RELATIVE
            add(imageElem)
            add(scaleBar)
        }
        add(imageContainerElem)
        val measureTool = MeasureTool.ActivateButton(imageContainerElem, imageDims, MeasureTool.showIn(scaleBar))
        rightDiv.add(0, measureTool)
    } else {
        val img = image(imageUrl, classes = setOf("full-width-image"))
        if (usePlaceholderOnError) img.ifNonExistentUsePlaceholder(size)
    }
}