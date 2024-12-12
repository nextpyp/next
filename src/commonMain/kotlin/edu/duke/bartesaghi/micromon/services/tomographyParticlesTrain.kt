package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.nodes.TomographyParticlesTrainNodeConfig
import edu.duke.bartesaghi.micromon.pyp.*
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import kotlinx.serialization.Serializable


@KVService
interface ITomographyParticlesTrainService {

	@KVBindingRoute("node/${TomographyParticlesTrainNodeConfig.ID}/addNode")
	suspend fun addNode(userId: String, projectId: String, inData: CommonJobData.DataId, args: TomographyParticlesTrainArgs): TomographyParticlesTrainData

	@KVBindingRoute("node/${TomographyParticlesTrainNodeConfig.ID}/edit")
	suspend fun edit(jobId: String, args: TomographyParticlesTrainArgs?): TomographyParticlesTrainData

	@KVBindingRoute("node/${TomographyParticlesTrainNodeConfig.ID}/get")
	suspend fun get(jobId: String): TomographyParticlesTrainData

	@KVBindingRoute("node/${TomographyParticlesTrainNodeConfig.ID}/getArgs")
	suspend fun getArgs(): String /* Args but serialized */

	companion object {

		fun resultsPath(jobId: String): String =
			"/kv/node/${TomographyParticlesTrainNodeConfig.ID}/$jobId/results"
	
	}
}


@Serializable
data class TomographyParticlesTrainArgs(
	val values: ArgValuesToml,
	val particlesName: String?
)

@Serializable
data class TomographyParticlesTrainData(
	override val common: CommonJobData,
	val args: JobArgs<TomographyParticlesTrainArgs>,
	val imageUrl: String
) : JobData {
	override fun isChanged() = args.hasNext()
	override fun finishedArgValues() = args.finished?.values
	override fun nextArgValues() = args.next?.values
}
