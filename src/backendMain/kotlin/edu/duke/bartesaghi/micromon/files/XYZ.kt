package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ObjectNode
import edu.duke.bartesaghi.micromon.getDoubleOrThrow
import org.bson.Document


/**
 * Coherence transfer function?
 *
 * XYZ because x- and z- and y-coordinates
 */
data class XYZ(
        val x: Double,
        val y: Double,
        val z: Double
) {

    companion object {

        fun from(json: ObjectNode) =
                XYZ(
                        x = json.getDoubleOrThrow("x"),
                        y = json.getDoubleOrThrow("y"),
                        z = json.getDoubleOrThrow("Z")
                )
    }

}

fun Document.readXYZ() =
        XYZ (
                x = getDouble("x"),
                y = getDouble("y"),
                z = getDouble("z")
        )

fun XYZ.toDoc() = Document().apply {
    set("x", x)
    set("y", y)
    set("z", z)
}
