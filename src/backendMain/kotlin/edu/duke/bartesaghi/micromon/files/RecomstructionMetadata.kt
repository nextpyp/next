package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ObjectNode
import edu.duke.bartesaghi.micromon.getDoubleOrThrow
import edu.duke.bartesaghi.micromon.services.ReconstructionMetaData
import org.bson.Document


fun ReconstructionMetaData.Companion.fromJson(json: ObjectNode) =
	ReconstructionMetaData(
		particlesTotal = json.getDoubleOrThrow("particles_total"),
		particlesUsed = json.getDoubleOrThrow("particles_used"),
		phaseResidual = json.getDoubleOrThrow("phase_residual"),
		occ = json.getDoubleOrThrow("occ"),
		logp = json.getDoubleOrThrow("logp"),
		sigma = json.getDoubleOrThrow("sigma")
	)

fun Document.readReconstructionMetadata() =
    ReconstructionMetaData(
        particlesTotal = getDouble("particles_total"),
        particlesUsed = getDouble("particles_used"),
        phaseResidual = getDouble("phase_residual"),
        occ = getDouble("occ"),
        logp = getDouble("logp"),
        sigma = getDouble("sigma")
    )

fun ReconstructionMetaData.toDoc() = Document().apply {
    set("particles_total", particlesTotal)
    set("particles_used", particlesUsed)
    set("phase_residual", phaseResidual)
    set("occ", occ)
    set("logp", logp)
    set("sigma", sigma)
}
