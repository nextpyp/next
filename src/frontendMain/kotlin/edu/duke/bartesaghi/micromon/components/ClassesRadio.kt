package edu.duke.bartesaghi.micromon.components


// the radio buttons and plots use a zero-based index, but the class number is one-based
fun Int.indexToClassNum() = this + 1
fun Int.classNumToIndex() = this - 1


class ClassesRadio(
	label: String,
) : RadioSelection(
	labelText = "$label: ",
	canMultiSelect = true
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
