package edu.duke.bartesaghi.micromon.components

import io.kvision.html.*
import js.UnshadowedCheck
import kotlinx.html.InputType


class RadioSelection(
	val labelText: String,
	var count: Int = 0,
	val canMultiSelect: Boolean = false,
	initiallyChecked: List<Int> = emptyList()
): Div(classes  = setOf("radio-selection-nav")) {

	companion object {
		var nextUniqueId: Int = 1
		fun makeUniqueId(): Int {
			val id = nextUniqueId
			nextUniqueId += 1
			return id
		}
	}

	private val uid = makeUniqueId()

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

    fun getCheckedIndices(): List<Int> =
		elements
			.withIndex()
			.mapNotNull { (index, check) ->
				index.takeIf { check.elem.checked }
			}

    fun setCheckedIndices(checked: List<Int>) {
        elements.forEachIndexed { index, check ->
            check.elem.checked = index in checked
        }
    }

    fun setCount(count: Int) {

		if (count == this.count) {
			return
		}

		val checkedIndices = getCheckedIndices()
		this.count = count
		this.removeAll()
		initializeCheckboxes(checkedIndices)
    }
}
