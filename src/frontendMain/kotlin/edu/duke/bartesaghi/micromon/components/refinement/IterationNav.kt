package edu.duke.bartesaghi.micromon.components.refinement

import edu.duke.bartesaghi.micromon.components.forms.enabled
import io.kvision.core.onEvent
import io.kvision.form.check.CheckBoxStyle
import io.kvision.form.check.checkBox
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span


class IterationNav (initialIterations: List<Int>) {

    private val instances = mutableListOf<Instance>()

	private val iterations = ArrayList(initialIterations)

	// start with the most recent iteration, if any
    var iteration: Int? = iterations.lastOrNull()
        private set

	var isLive: Boolean = true

	fun addIteration(iteration: Int) {
		iterations.add(iteration)
		if (isLive) {
			setIteration(iteration)
		} else {
			updateAllDisplays()
		}
	}

    fun setIteration(iteration: Int, stopLive: Boolean = false) {
		if (stopLive) {
			isLive = false
		}
        this.iteration = iteration
		updateAllDisplays()
        fireIterationChange()
    }

	fun setLive(isLive: Boolean) {
		this.isLive = isLive
		updateAllDisplays()
		if (isLive) {
			iterations.lastOrNull()?.let { setIteration(it) }
		}
	}

    private fun updateAllDisplays() {
        instances.forEach { it.updateDisplay() }
    }

    private fun fireIterationChange() {
		val iteration = iteration
			?: return
		instances.forEach { it.onIterationChange(iteration) }
    }

    fun makeInstance(): Instance {
        val instance = Instance()
        instances.add(instance)
        return instance
    }


    /**
     * Note that instances of this inner class can have their own callbacks for when the index is changed.
     */
    inner class Instance: Div(classes = setOf("numeric-nav")) {

		var onIterationChange: (Int) -> Unit = {}

        private val container = div(classes = setOf("nav-container"))

        private val navFirst = container.button("", icon = "fas fa-step-backward").apply {
            onClick {
				iterations.firstOrNull()?.let { setIteration(it, true) }
            }
            enabled = false
        }

        private val navBack10 = container.button("10", icon = "fas fa-angle-double-left").apply {
            onClick {
				iteration?.let { setIteration(it - 10, true) }
            }
            enabled = false
        }

        private val navBack = container.button("1", icon = "fas fa-angle-left").apply {
            onClick {
				iteration?.let { setIteration(it - 1, true) }
            }
            enabled = false
        }

        private val navCounter = container.span(classes = setOf("counter"))

        private val navForward = container.button("1", icon = "fas fa-angle-right").apply {
            onClick {
				iteration?.let { setIteration(it + 1, true) }
            }
            enabled = false
        }

        private val navForward10 = container.button("10", icon = "fas fa-angle-double-right").apply {
            onClick {
				iteration?.let { setIteration(it + 10, true) }
            }
            enabled = false
        }

        private val navLast = container.button("", icon = "fas fa-step-forward").apply {
            onClick {
				iterations.lastOrNull()?.let { setIteration(it, true) }
            }
            enabled = false
        }

        private val navLive = container.checkBox(isLive, label = "Watch").apply {
            style = CheckBoxStyle.PRIMARY
            onEvent {
                change = {
					setLive(value)
                }
            }
        }

        fun updateDisplay() {

			val iteration = iteration
			val firstIteration = iterations.firstOrNull()
			val lastIteration = iterations.lastOrNull()

            navFirst.enabled = iteration != null && firstIteration != null && iteration > firstIteration
            navBack10.enabled = iteration != null && firstIteration != null && iteration >= firstIteration + 10
            navBack.enabled = navFirst.enabled

			navCounter.content =
				if (iteration != null && lastIteration != null) {
					"Iteration $iteration of $lastIteration"
				} else {
					""
				}

            navForward.enabled = iteration != null && lastIteration != null && iteration < lastIteration
            navForward10.enabled = iteration != null && lastIteration != null && iteration <= lastIteration - 10
            navLast.enabled = navForward.enabled

            navLive.value = isLive
        }

        init {
            updateDisplay()
        }
    }
}
