package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ObjectNode
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.getListOfDocuments
import edu.duke.bartesaghi.micromon.mongo.getListOfDoubles
import edu.duke.bartesaghi.micromon.mongo.getListOfListsOfDocuments
import org.bson.Document

/**
 * DMD = D rift M eta D ata
 */
data class DMD(
    val tilts: List<Double>,
    val drifts: List<List<DriftXY>>,
    val ctfValues: List<CtfTiltData>,
    val ctfProfiles: List<AVGROT>,
    val tiltAxisAngle: Double
) {

    data class DriftXY(
        val x: Double,
        val y: Double,
    )

    data class CtfTiltData(
        val index: Int,
        val defocus1: Double,
        val defocus2: Double,
        val astigmatism: Double,
        val cc: Double,
        val resolution: Double
    )

    data class CtfTiltProfile(
        val spatialFreq: List<Double>, // (1 / Angstroms)
        val avgRotNoAstig: List<Double>,
        val avgRot: List<Double>,
        val ctfFit: List<Double>,
        val crossCorrelation: List<Double>,
        val twoSigma: List<Double>
    )

    companion object {

        fun from(json: ObjectNode): DMD {
            val drifts = json.getArrayOrThrow("drift")
            val ctfValues = json.getArrayOrThrow("ctf_values")
            val ctfProfiles = json.getArrayOrThrow("ctf_profiles")
            val tiltAxisAngle = json.getDoubleOrThrow("tilt_axis_angle")
            return DMD(
                tilts = json.getArrayOrThrow("tilts").toListOfDoubles(),
                drifts = drifts.indices().map { i ->
                    val driftArray = drifts.getArrayOrThrow(i, "drifts[$i]")
                    driftArray.indices().map { j ->
                        val drift = driftArray.getArrayOrThrow(j, "drifts[$i][$j]")
                        DriftXY(
                            drift.getDoubleOrThrow(0, "drifts[$i][$j].x"),
                            drift.getDoubleOrThrow(1, "drifts[$i][$j].y")
                        )
                    }

                },
                ctfValues = ctfValues.indices().map { i ->
                    val ctfTiltData = ctfValues.getArrayOrThrow(i, "ctfValues[$i]")
                    CtfTiltData(
                        ctfTiltData.getDoubleOrThrow(0, "ctfValues[$i].index").toInt(),
                        ctfTiltData.getDoubleOrThrow(1, "ctfValues[$i].defocus1"),
                        ctfTiltData.getDoubleOrThrow(2, "ctfValues[$i].defocus2"),
                        ctfTiltData.getDoubleOrThrow(3, "ctfValues[$i].astigmatism"),
                        ctfTiltData.getDoubleOrThrow(4, "ctfValues[$i].cc"),
                        ctfTiltData.getDoubleOrThrow(5, "ctfValues[$i].resolution"),
                    )
                },
                ctfProfiles = ctfProfiles.indices().map { i ->
                    val ctfProfileJson = ctfProfiles.getArrayOrThrow(i, "ctfProfiles[$i]")
                    val ctfProfile = CtfTiltProfile(
                        ctfProfileJson.getArrayOrThrow(0, "ctfProfiles[$i].spatialFreq").toListOfDoubles(),
                        ctfProfileJson.getArrayOrThrow(1, "ctfProfiles[$i].avgRotNoAstig").toListOfDoubles(),
                        ctfProfileJson.getArrayOrThrow(2, "ctfProfiles[$i].avgRot").toListOfDoubles(),
                        ctfProfileJson.getArrayOrThrow(3, "ctfProfiles[$i].ctfFit").toListOfDoubles(),
                        ctfProfileJson.getArrayOrThrow(4, "ctfProfiles[$i].crossCorrelation").toListOfDoubles(),
                        ctfProfileJson.getArrayOrThrow(5, "ctfProfiles[$i].twoSigma").toListOfDoubles(),
                    )
                    val listLength = ctfProfile.spatialFreq.size
                    if (listOf(ctfProfile.avgRotNoAstig, ctfProfile.avgRot, ctfProfile.ctfFit, ctfProfile.crossCorrelation, ctfProfile.twoSigma).any { it.size != listLength})
                        throw RuntimeException("Mismatched number of elements in each line for CTF profile")
                    AVGROT(List(ctfProfile.spatialFreq.size) { index ->
                        AVGROT.Sample(
                            ctfProfile.spatialFreq[index],
                            ctfProfile.avgRotNoAstig[index],
                            ctfProfile.avgRot[index],
                            ctfProfile.ctfFit[index],
                            ctfProfile.crossCorrelation[index],
                            ctfProfile.twoSigma[index]
                        )
                    })
                },
                tiltAxisAngle = tiltAxisAngle
            )
        }

		fun empty() =
			DMD(
				emptyList(),
				emptyList(),
				emptyList(),
				emptyList(),
				0.0
			)
    }
}

fun Document.readDMD() = DMD(
    getListOfDoubles("tilts"),
    getListOfListsOfDocuments("drifts").map { it.map { innerIt -> innerIt.readDriftXY() } },
    getListOfDocuments("ctf_values")?.map { it.readCtfTiltData() } ?: emptyList(),
    getListOfDocuments("ctf_profiles")?.map { it.readAVGROT() } ?: emptyList(),
    getDouble("tilt_axis_angle")
)

fun DMD.toDoc() = Document().apply {
    set("tilts", tilts)
    set("drifts", drifts.map { it.map { innerIt -> innerIt.toDoc() } })
    set("ctf_values", ctfValues.map { it.toDoc() })
    set("ctf_profiles", ctfProfiles.map { it.toDoc() })
    set("tilt_axis_angle", tiltAxisAngle)
}

fun Document.readDriftXY() = DMD.DriftXY(
    x = getDouble("x"),
    y = getDouble("y")
)

fun DMD.DriftXY.toDoc() = Document().apply {
    set("x", x)
    set("y", y)
}

fun Document.readCtfTiltData() = DMD.CtfTiltData(
    index = getInteger("index"),
    defocus1 = getDouble("defocus1"),
    defocus2 = getDouble("defocus2"),
    astigmatism = getDouble("astigmatism"),
    cc = getDouble("cc"),
    resolution = getDouble("resolution"),
)

fun DMD.CtfTiltData.toDoc() = Document().apply {
    set("index", index)
    set("defocus1", defocus1)
    set("defocus2", defocus2)
    set("astigmatism", astigmatism)
    set("cc", cc)
    set("resolution", resolution)
}
