package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ObjectNode
import edu.duke.bartesaghi.micromon.getDoubleOrThrow
import org.bson.Document


/**
 * Coherence transfer function?
 *
 * XY because x- and y-coordinates
 */
data class XY(
        val x: Double,
        val y: Double
) {

    companion object {

        fun from(json: ObjectNode) =
                XY(
                        x = json.getDoubleOrThrow("x"),
                        y = json.getDoubleOrThrow("y")
                )
    }

}

fun Document.readXY() =
        XY (
                x = getDouble("x"),
                y = getDouble("y")
        )

fun XY.toDoc() = Document().apply {
    set("x", x)
    set("y", y)
}
