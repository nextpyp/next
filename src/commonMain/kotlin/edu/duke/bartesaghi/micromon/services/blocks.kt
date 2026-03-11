package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.Identified
import edu.duke.bartesaghi.micromon.pyp.ImageDims
import io.kvision.annotations.KVBindingRoute
import io.kvision.annotations.KVService
import io.kvision.remote.RemoteOption
import kotlinx.serialization.Serializable


/** Filter set: Table (micrograph-based) and PrismPyp (class-based) are stored and loaded separately. */
@Serializable
enum class PreprocessingFilterType {
	TABLE,
	PRISMPYP
}

@KVService
interface IBlocksService {

	@KVBindingRoute("blocks/listFilters")
	suspend fun listFilters(jobId: String, filterType: PreprocessingFilterType): List<String>

	@KVBindingRoute("blocks/getFilter")
	suspend fun getFilter(jobId: String, name: String, filterType: PreprocessingFilterType): PreprocessingFilter

	@KVBindingRoute("blocks/saveFilter")
	suspend fun saveFilter(jobId: String, filter: PreprocessingFilter, filterType: PreprocessingFilterType)

	@KVBindingRoute("blocks/deleteFilter")
	suspend fun deleteFilter(jobId: String, name: String, filterType: PreprocessingFilterType)

	/** state = "$jobId,$filterType" (e.g. "job123,TABLE" or "job123,PRISMPYP") */
	@KVBindingRoute("blocks/filterOptions")
	suspend fun filterOptions(search: String?, initial: String?, state: String?): List<RemoteOption>
}


interface HasID {
	val id: String
}

interface PreprocessingData : HasID {

	override val id: String
	val timestamp: Long

	val ccc: Double
	val cccc: Double
	val defocus1: Double
	val defocus2: Double
	val angleAstig: Double
	val averageMotion: Double
	val numAutoParticles: Int
	val imageDims: ImageDims
}


interface PreprocessingDataProperty : Identified {

	/** must be unique among all implementors of this interface */
	override val id: String

	val label: String
}


@Serializable
data class PreprocessingPropRange(

	/** must be one of PreprocessingDataProperty.id */
	val propId: String,

	val min: Double,
	val max: Double
)

@Serializable
data class PreprocessingFilter(
	val name: String,
	val ranges: List<PreprocessingPropRange>,
	val excludedIds: List<String>,
	/** Legacy: when present, treated as both real and fourier. Prefer selectedClassesReal/Fourier. */
	val selectedClasses: List<Int>? = null,
	/** Class-based filter: selected class numbers for real space. */
	val selectedClassesReal: List<Int>? = null,
	/** Class-based filter: selected class numbers for Fourier space. */
	val selectedClassesFourier: List<Int>? = null
)


/**
 * tragically, KVision can't handle RemoteSelect values that are null or empty string,
 * so we need to use a special value to encode None, and prevent a user from picking it as a filter name
 */
const val NoneFilterOption = "__NONE__"


@Serializable
data class FileDownloadData(
	val bytes: Long
)
