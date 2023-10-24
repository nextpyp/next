package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.falseToNull
import io.kvision.core.Component
import io.kvision.tabulator.SortingDir
import io.kvision.tabulator.Tabulator
import io.kvision.utils.toKotlinObj
import io.kvision.tabulator.js.Tabulator as TabulatorJs


/**
 * a bug in KVision prevents us from using mosts class directly with the Tabulator
 * https://github.com/rjaros/kvision/issues/275
 *
 * This class implements a general workaround that gives the Tabulator a proxy to the
 * item, instead of the item itself.
 */
class TabulatorProxy<T> {

	var items: List<T> = emptyList()
		set(value) {
			field = value
			update()
		}

	var filter: (T) -> Boolean = { true }
		set(value) {
			field = value
			update()
		}

	var tabulator: Tabulator<Key>? = null

	data class Key(val index: Int)

	val tabulatorOrThrow: Tabulator<Key> get() =
		tabulator ?: throw NoSuchElementException("set the tabulator before using the proxy")

	fun resolve(key: Key): T =
		items.get(key.index)

	private fun update() {
		tabulatorOrThrow.setData(items
			.withIndex()
			.filter { (_, item) -> filter(item) }
			.map { (i, _) -> Key(i) }
			.toTypedArray()
		)
	}

	fun updateItem(index: Int) {
		val row = tabulatorOrThrow.jsTabulator
			?.getRowFromPosition(index, true)
			.falseToNull()
			?: return
		row.reformat()
	}

	fun formatter(formatter: (T) -> Component): TabulatorFormatter<Key> {
		return { _, _, key ->
			formatter(resolve(key))
		}
	}

	private val TabulatorJs.RowComponent.key: Key get() =
		toKotlinObj(getData(), Key::class)

	fun <C:Comparable<C>> sorter(selector: (T) -> C?): TabulatorSorter {
		return { _, _, aRow, bRow, _, _, _ ->

			// get the column values to be sorted from the row info
			val a = selector(resolve(aRow.key))
			val b = selector(resolve(bRow.key))

			// compare the values, pushing null values to the end of the sort
			if (a != null && b != null) {
				a.compareTo(b)
			} else if (a != null) {
				-1
			} else if (b != null) {
				1
			} else {
				0
			}
		}
	}
}

typealias TabulatorFormatter<T> = (
	cell: TabulatorJs.CellComponent,
	onRendered: (callback: () -> Unit) -> Unit,
	data: T
) -> Component

typealias TabulatorSorter = (
	a: dynamic,
	b: dynamic,
	aRow: TabulatorJs.RowComponent,
	bRow: TabulatorJs.RowComponent,
	column: TabulatorJs.ColumnComponent,
	dir: SortingDir,
	// NOTE: Tragically, the KVision wrapper types seem to be wrong on this one. =(
	// The `dir` value passed to this callback is actually a string at runtime, not a `SortingDir`.
	// We don't need to implement sorting direction corrections ourselves in the callback anyway though,
	// so probably no one here cares about the real type.
	// Nevertheless, the real type is recorded here for posterity.
	sorterParams: dynamic
) -> Int
