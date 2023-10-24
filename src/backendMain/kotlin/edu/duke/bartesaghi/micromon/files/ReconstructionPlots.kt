package edu.duke.bartesaghi.micromon.files

import com.fasterxml.jackson.databind.node.ObjectNode
import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.mongo.getDocument
import edu.duke.bartesaghi.micromon.mongo.getListOfDoubles
import edu.duke.bartesaghi.micromon.mongo.getListOfListsOfDoubles
import edu.duke.bartesaghi.micromon.services.ReconstructionPlots
import org.bson.Document


fun ReconstructionPlots.Companion.fromJson(json: ObjectNode) =
	ReconstructionPlots(
		defRotHistogram = json.getArrayOrThrow("def_rot_histogram").toListOfListOfDoubles(),
		defRotScores = json.getArrayOrThrow("def_rot_scores").toListOfListOfDoubles(),
		rotHist = ReconstructionPlots.Histogram.fromJson(json.getObjectOrThrow("rot_hist")),
		defHist = ReconstructionPlots.Histogram.fromJson(json.getObjectOrThrow("def_hist")),
		scoresHist = ReconstructionPlots.Histogram.fromJson(json.getObjectOrThrow("scores_hist")),
		occHist = ReconstructionPlots.Histogram.fromJson(json.getObjectOrThrow("occ_hist")),
		logpHist = ReconstructionPlots.Histogram.fromJson(json.getObjectOrThrow("logp_hist")),
		sigmaHist = ReconstructionPlots.Histogram.fromJson(json.getObjectOrThrow("sigma_hist")),
		occPlot = json.getArray("occ_plot")?.map { it.asDouble() }
	)

fun Document.readReconstructionPlotData() =
    ReconstructionPlots(
        defRotHistogram = getListOfListsOfDoubles("def_rot_histogram"),
        defRotScores =    getListOfListsOfDoubles("def_rot_scores"),
        rotHist    = getDocument("rot_hist")   .readRPDHistogramData(),
        defHist    = getDocument("def_hist")   .readRPDHistogramData(),
        scoresHist = getDocument("scores_hist").readRPDHistogramData(),
        occHist    = getDocument("occ_hist")   .readRPDHistogramData(),
        logpHist   = getDocument("logp_hist")  .readRPDHistogramData(),
        sigmaHist  = getDocument("sigma_hist") .readRPDHistogramData(),
        occPlot = getListOfDoubles("occ_plot")
    )

fun ReconstructionPlots.toDoc() = Document().apply {
    set("def_rot_histogram", defRotHistogram)
    set("def_rot_scores",    defRotScores)
    set("rot_hist",    rotHist   .toDoc())
    set("def_hist",    defHist   .toDoc())
    set("scores_hist", scoresHist.toDoc())
    set("occ_hist",    occHist   .toDoc())
    set("logp_hist",   logpHist  .toDoc())
    set("sigma_hist",  sigmaHist .toDoc())
    set("occ_plot", occPlot)
}


fun ReconstructionPlots.Histogram.Companion.fromJson(json: ObjectNode) =
	ReconstructionPlots.Histogram(
		n = json.getArrayOrThrow("n").map { it.doubleValue() },
		bins = json.getArrayOrThrow("bins").map { it.doubleValue() }
	)

fun Document.readRPDHistogramData() = ReconstructionPlots.Histogram(
    n = getListOfDoubles("n"),
    bins = getListOfDoubles("bins")
)

fun ReconstructionPlots.Histogram.toDoc() = Document().apply {
    set("n", n)
    set("bins", bins)
}
