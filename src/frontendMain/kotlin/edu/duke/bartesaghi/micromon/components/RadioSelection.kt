package edu.duke.bartesaghi.micromon.components

import io.kvision.html.*
import js.UnshadowedCheck
import kotlinx.html.InputType


open class RadioSelection(
	val labelText: String,
	initialCount: Int = 0,
	val canMultiSelect: Boolean = false,
	initiallyChecked: List<Int> = emptyList()
): Div(classes  = setOf("radio-selection-nav")) {

	companion object {
		private var nextUniqueId: Int = 1
		fun makeUniqueId(): Int {
			val id = nextUniqueId
			nextUniqueId += 1
			return id
		}
	}

	private val uid = makeUniqueId()

	var count: Int = initialCount
		set(value) {

			if (field == value) {
				return
			}
			field = value

			initializeCheckboxes(checkedIndices)
		}


	var onUpdate: () -> Unit = {}

    private val elements = ArrayList<UnshadowedCheck>()

    init {
        initializeCheckboxes(initiallyChecked)
    }

    private fun initializeCheckboxes(initiallyChecked: List<Int>) {

		removeAll()
		elements.clear()

		// because Kotlin DSLs can be dumb sometimes
		val me = this

		div {

            label(me.labelText, classes=setOf("radio-label"))

			if (me.count > 0) {

				// make the checkboxes
				for (index in 0 until me.count) {
					div(classes = setOf("radio-holder")) {
						label(classes = setOf("radio-selection")) {

							// make the actual checkbox element
							val name = "radio-selection-group-${me.uid}"
							val classes = setOf("radio-selection-check")
							val check = if (me.canMultiSelect) {
								UnshadowedCheck(InputType.checkBox, name, classes)
							} else {
								UnshadowedCheck(InputType.radio, name, classes)
							}

							check.elem.checked = index in initiallyChecked

							// wire up events
							check.elem.onclick = {
								me.onUpdate()
							}

							add(check)
							me.elements.add(check)
							span((index + 1).toString())
						}
					}
				}
			} else {
				span("(None)")
			}
        }

        if (canMultiSelect) {

            button("Hide All").onClick {
                for (check in elements) {
                    check.elem.checked = false
                }
				onUpdate()
            }

            button("Show All").onClick {
                for (check in elements) {
					check.elem.checked = true
                }
				onUpdate()
            }
        }
    }

	var checkedIndices: List<Int>
		get() = elements
			.withIndex()
			.mapNotNull { (index, check) ->
				index.takeIf { check.elem.checked }
			}
		set(checked) {
			elements.forEachIndexed { index, check ->
				check.elem.checked = index in checked
			}
		}

	fun toggleIndex(index: Int) {

		// toggle the class on or off
		val indices = checkedIndices
			.toMutableList()
		if (index in indices) {
			indices.remove(index)
		} else {
			indices.add(index)
		}

		checkedIndices = indices
	}

	fun allIndices(): List<Int> =
		(0 until count).toList()

	fun toggleFocusIndex(index: Int) {

		// toggle focus on one index, or show all indices
		checkedIndices =
			if (checkedIndices == listOf(index)) {
				allIndices()
			} else {
				listOf(index)
			}
	}
}
