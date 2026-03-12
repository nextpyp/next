package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.services.ImageSize
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.image

/**
 * Panel slug used in prismpyp image URLs.
 */
enum class PrismPypPanel(val title: String, val slug: String) {
	TotalLoss("Total loss", "total_loss"),
	CollapseLevel("Collapse level", "collapse_level"),
	Umap("UMAP", "thumbnail_plot_umap"),
	NearestNeighbors("Nearest neighbors", "nearest_neighbors_matrix"),
	Clustering("Clustering", "scatter_plot_UMAP");
}

/**
 * Path for a prismpyp webp image. Backend should serve the image at this path.
 */
fun prismpypImagePath(jobId: String, space: String, panel: PrismPypPanel, size: ImageSize): String =
	"/kv/jobs/$jobId/prismpyp/$space/${panel.slug}/${size.id}"

/** Which panels to show; null = all five. */
private val allPanels = listOf(
	PrismPypPanel.TotalLoss,
	PrismPypPanel.CollapseLevel,
	PrismPypPanel.Umap,
	PrismPypPanel.NearestNeighbors,
	PrismPypPanel.Clustering
)

/** Storage getter/setter for each panel (for size persistence). */
private fun panelStorage(panel: PrismPypPanel): Pair<() -> ImageSize?, (ImageSize) -> Unit> = when (panel) {
	PrismPypPanel.TotalLoss -> ({ Storage.prismpypTotalLossSize } to { Storage.prismpypTotalLossSize = it })
	PrismPypPanel.CollapseLevel -> ({ Storage.prismpypCollapseLevelSize } to { Storage.prismpypCollapseLevelSize = it })
	PrismPypPanel.Umap -> ({ Storage.prismpypUmapSize } to { Storage.prismpypUmapSize = it })
	PrismPypPanel.NearestNeighbors -> ({ Storage.prismpypNearestNeighborsSize } to { Storage.prismpypNearestNeighborsSize = it })
	PrismPypPanel.Clustering -> ({ Storage.prismpypClusteringSize } to { Storage.prismpypClusteringSize = it })
}

/**
 * Five resizable panels displaying prismpyp webp images for a given space (e.g. "real" or "fourier").
 */
class PrismPypPanels(
	val jobId: String,
	val space: String,
	panels: List<PrismPypPanel>? = null,
	classes: Set<String> = emptySet()
) : Div(classes = setOf("prismpyp-panels") + classes) {

	private val panelList = panels ?: allPanels

	init {
		val inner = div(classes = setOf("prismpyp-panels-inner"))
		add(inner)
		for (p in panelList) {
			val (getSize, setSize) = panelStorage(p)
			inner.add(panel(p, getSize, setSize))
		}
	}

	private fun panel(
		panel: PrismPypPanel,
		getSize: () -> ImageSize?,
		setSize: (ImageSize) -> Unit
	): SizedPanel {
		val jId = jobId
		val sp = space
		val useXLarge = (panel == PrismPypPanel.NearestNeighbors || panel == PrismPypPanel.Umap)
		return SizedPanel(panel.title, getSize(), includeXLarge = useXLarge).apply {
			val img = image(prismpypImagePath(jId, sp, panel, this.size), classes = setOf("full-width-image"))
			add(img)
			onResize = { newSize: ImageSize ->
				img.src = prismpypImagePath(jId, sp, panel, newSize)
				setSize(newSize)
			}
		}
	}
}
