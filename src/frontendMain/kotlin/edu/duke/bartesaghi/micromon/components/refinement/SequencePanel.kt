package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.Storage
import edu.duke.bartesaghi.micromon.components.ArbitrarySizedPanel
import edu.duke.bartesaghi.micromon.components.forms.enabled
import io.kvision.core.Display
import io.kvision.html.*


/**
* Show a single image in a resiable panel, with prev/next buttons
*/
class SequencePanel(
	title: String,
	sizes: List<Int>,
	onPrev: () -> Unit = {},
	onNext: () -> Unit = {},
	onClose: () -> Unit = {},
	onResize: () -> Unit = {},
	classes: Set<String> = emptySet()
) : ArbitrarySizedPanel(title, sizes, Storage.refinementsTabPanelSizeIndex, classes = classes) {

    val elem = Div(null, classes = setOf("full-width-image"))

    private val prevButton = Button(
		"Previous image",
		"fas fa-angle-left",
		classes = setOf("prev-next-button")
	).onClick {
        onPrev()
    }.apply {
		enabled = false
    }

    private val nextButton = Button(
		"",
		classes = setOf("prev-next-button"),
	).onClick {
        onNext()
    }.apply {
		// show the icon after the text
		span("Next image ")
		i(classes = setOf("fas", "fa-angle-right"))
		enabled = false
    }


    init {

		// start off hidden, until we show something
		display = Display.NONE

		// layout the UI
		add(elem)
		div(classes = setOf("single-image-controls")) {
			add(this@SequencePanel.prevButton)
			add(this@SequencePanel.nextButton)
		}

		// add a close button to the sized panel, next to the +/- buttons
        rightDiv.button(
			"",
			"fas fa-times",
			classes = setOf("red-close-button")
		).onClick {
			onClose()
		}

        // set the panel resize handler
        this.onResize = { index: Int ->
			// save the new size
			Storage.refinementsTabPanelSizeIndex = index
            onResize()
        }
    }

	fun update(canPrev: Boolean, canNext: Boolean) {
		prevButton.enabled = canPrev
		nextButton.enabled = canNext
	}
}
