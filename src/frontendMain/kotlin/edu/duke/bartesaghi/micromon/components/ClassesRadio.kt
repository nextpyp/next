package edu.duke.bartesaghi.micromon.components


// the radio buttons and plots use a zero-based index, but the class number is one-based
fun Int.indexToClassNum() = this + 1
fun Int.classNumToIndex() = this - 1


class ClassesRadio(
	label: String,
	canMultiSelect: Boolean,
	deselectAllLabel: String = "Hide All",
	selectAllLabel: String = "Show All",
	/** If true, button labels are 0-based (0, 1, 2, ...); otherwise 1-based (1, 2, 3, ...). */
	val startAtZero: Boolean = false
) : RadioSelection(
	labelText = "$label: ",
	canMultiSelect = canMultiSelect,
	deselectAllLabel = deselectAllLabel,
	selectAllLabel = selectAllLabel,
	labelOffset = if (startAtZero) 0 else 1
) {

	var checkedClasses: List<Int>
		get() = checkedIndices.map { it.indexToClassNum() }
		set(checked) { checkedIndices = checked.map { it.classNumToIndex() } }

	fun toggleClass(classNum: Int) =
		toggleIndex(classNum.classNumToIndex())

	fun toggleFocusClass(classNum: Int) =
		toggleFocusIndex(classNum.classNumToIndex())

	fun allClasses(): List<Int> =
		allIndices()
			.map { it.indexToClassNum() }
}
