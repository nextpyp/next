package edu.duke.bartesaghi.micromon.components

import edu.duke.bartesaghi.micromon.AppScope
import edu.duke.bartesaghi.micromon.components.forms.ArgsForm
import edu.duke.bartesaghi.micromon.components.forms.addSaveButton
import edu.duke.bartesaghi.micromon.components.forms.formPanel
import edu.duke.bartesaghi.micromon.errorMessage
import edu.duke.bartesaghi.micromon.loading
import edu.duke.bartesaghi.micromon.nodes.Workflow
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.services.Services
import io.kvision.form.FormType
import io.kvision.modal.Modal
import kotlinx.serialization.Serializable


class WorkflowImportDialog(
	val workflow: Workflow
) {

	fun show(callback: (Workflow, ArgValuesToml) -> Unit) {

		val win = Modal(
			caption = "Import workflow: ${workflow.name}",
			escape = true,
			closeButton = true,
			classes = setOf("dashboard-popup", "args-form-popup", "max-height-dialog")
		)

		// wait for loads from the server
		val loading = win.loading("Loading parameters ...")
		win.show()
		AppScope.launch {

			val pypArgs = try {
				Args.fromJson(Services.projects.workflowArgs(workflow.id))
			} catch (t: Throwable) {
				win.errorMessage(t)
				return@launch
			} finally {
				win.remove(loading)
			}

			val form = win.formPanel<FormContent>(type = FormType.HORIZONTAL) {
				add(FormContent::values, ArgsForm(pypArgs, emptyList()))
			}

			win.addSaveButton(form) { formContent ->
				callback(workflow, formContent.values)
			}
		}
	}
}

@Serializable
data class FormContent(
	val values: ArgValuesToml
)
