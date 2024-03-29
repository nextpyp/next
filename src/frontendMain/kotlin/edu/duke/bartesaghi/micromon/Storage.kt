package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.services.ImageSize
import edu.duke.bartesaghi.micromon.services.MicrographProp
import edu.duke.bartesaghi.micromon.services.TiltSeriesProp
import edu.duke.bartesaghi.micromon.views.DashboardView
import kotlinx.browser.localStorage
import kotlin.reflect.KProperty


object Storage {

	private fun get(key: String): String? =
		localStorage.getItem(key)

	private fun set(key: String, value: String?) =
		if (value != null) {
			localStorage.setItem(key, value)
		} else {
			localStorage.removeItem(key)
		}

	private val ids = HashSet<String>()

	open class StorageItem<T>(
		val id: String,
		val serialize: (T) -> String?,
		val deserialize: (String?) -> T
	) {

		init {
			// make sure there's not an id collision
			if (id in ids) {
				throw IllegalStateException("duplicate storage id: $id")
			}
			ids.add(id)
		}

		operator fun getValue(thisRef: Any?, property: KProperty<*>): T =
			deserialize(get(id))

		operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
			set(id, serialize(value))
		}
	}

	class StringStorageItem(
		id: String
	) : StorageItem<String?>(
		id,
		serialize = { it },
		deserialize = { it }
	)

	class IntStorageItem(
		id: String
	) : StorageItem<Int?>(
		id,
		serialize = { it?.toString() },
		deserialize = { it?.toIntOrNull() }
	)

	class BoolStorageItem(
		id: String
	) : StorageItem<Boolean?>(
		id,
		serialize = { it?.toString() },
		deserialize = { it?.toBoolean() }
	)

	open class EnumStorageItem<T>(
		id: String,
		val values: Array<T>
	) : StorageItem<T?>(
		id,
		serialize = { it?.id },
		deserialize = { enumId -> values.find { it.id == enumId } }
	)
	where T: Enum<T>, T: Identified

	class ImageSizeStorageItem(id: String) : EnumStorageItem<ImageSize>(id, ImageSize.values())
	class MicrographPropStorageItem(id: String) : EnumStorageItem<MicrographProp>(id, MicrographProp.values())
	class TiltSeriesPropStorageItem(id: String) : EnumStorageItem<TiltSeriesProp>(id, TiltSeriesProp.values())

	var micrographSize by ImageSizeStorageItem("micrographSize")
	var tiltSeriesSize by ImageSizeStorageItem("tiltSeriesSize")
	var refinementsTabPanelSizeIndex by IntStorageItem("refinementsTabPanelSizeIndex")
	var spriteAlignedTiltSeriesSize  by ImageSizeStorageItem("spriteAlignedTiltSeriesSize")
	var spriteReconstructionTiltSeriesSize by ImageSizeStorageItem("spriteReconstructionTiltSeriesSize")
	var spriteCtf2dPlotSize by ImageSizeStorageItem("spriteCtf2dPlotSize")
	var spriteTiltSeriesMicrographSize by ImageSizeStorageItem("spriteTiltSeriesMicrographSize")
	var tomographyPreprocessingSideViewSize by ImageSizeStorageItem("tomographyPreprocessingSideViewSize")
	var frameMotionDriftSize by ImageSizeStorageItem("frameMotionDriftSize")
	var ctfMultiTiltSize by ImageSizeStorageItem("ctfMultiTiltSize")
	var avgRotSize by ImageSizeStorageItem("avgRotSize")
	var FSCPlotSize by ImageSizeStorageItem("FSCPlotSize")
	var PRPlotsSize by ImageSizeStorageItem("PRPlotsSize")
	var PRHistogramSize by ImageSizeStorageItem("PRHistogramSize")
	var ThreeDViewSize by ImageSizeStorageItem("ThreeDViewSize")
	var classViewImageSize by ImageSizeStorageItem("classViewImageSize")
	var ClassFSCPlotSize by ImageSizeStorageItem("ClassFSCPlotSize")
	var ClassOccPlotSize by ImageSizeStorageItem("ClassOccPlotSize")
	var motionSize by ImageSizeStorageItem("motionSize")
	var ctffindSize by ImageSizeStorageItem("ctffindSize")
	var filterTableRows by IntStorageItem("filterTableRows")
	var singleParticlePreprocessingTabIndex by IntStorageItem("singleParticlePreprocessingTabIndex")
	var singleParticleSessionDataTabIndex by IntStorageItem("singleParticleSessionDataIndex")
	var singleParticleImportDataTabIndex by IntStorageItem("singleParticleImportDataIndex")
	var tomographySessionDataTabIndex by IntStorageItem("tomographySessionDataIndex")
	var tomographyImportDataTabIndex by IntStorageItem("tomographyImportDataIndex")
	var integratedRefinementTabIndex by IntStorageItem("integratedRefinementTabIndex")
	var tomographyPreprocessingTabIndex by IntStorageItem("tomographyPreprocessingTabIndex")
	var tomographyPreprocessingMultiPanelTabIndex by IntStorageItem("tomographyPreprocessingMultiPanelTabIndex")
	var threeJsDoubleViews by BoolStorageItem("threeJsDoubleViews")
	var showParticles by BoolStorageItem("showParticles")
	var showVirions by BoolStorageItem("showVirions")
	var showSpikes by BoolStorageItem("showSpikes")
	var micrographSort by MicrographPropStorageItem("micrographSort")
	var tiltSeriesSort by TiltSeriesPropStorageItem("tiltSeriesSort")
	var logWrapLines by BoolStorageItem("logWrapLines")
	var logShowDebugInfo by BoolStorageItem("logShowDebugInfo")
	var projectSharingFilter by EnumStorageItem("projectSharingFilter", DashboardView.SharingOption.values())
	var twodClassesImageSize by ImageSizeStorageItem("twodClassesImageSize")
	var classesMovieSize by ImageSizeStorageItem("classesMovieSize")
	var perParticleScoresSize by ImageSizeStorageItem("perParticleScoresSize")
}
