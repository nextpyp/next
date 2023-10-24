package edu.duke.bartesaghi.micromon.components.forms

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import io.kvision.core.Container
import io.kvision.form.FormControl
import io.kvision.form.FormPanel
import io.kvision.form.FormType
import kotlinx.serialization.InternalSerializationApi
import kotlin.OptIn
import kotlin.reflect.KProperty1


/**
 * make addInternal() public so we can use more interesting kinds of form controls
 */
class OpenFormPanel<K:Any>(
	type: FormType? = null,
	classes: Set<String> = setOf(),
	serializer: KSerializer<K>
) : FormPanel<K>(
	type = type,
	classes = classes,
	serializer = serializer
) {

	fun <C:FormControl> add(
		key: KProperty1<K,Any?>,
		control: C,
		required: Boolean = false,
		requiredMessage: String? = null,
		legend: String? = null,
		validatorMessage: ((C) -> String?)? = null,
		validator: ((C) -> Boolean?)? = null
	): FormPanel<K> {
		return addInternal(key, control, required, requiredMessage, legend, validatorMessage, validator)
	}

	companion object {

		@OptIn(InternalSerializationApi::class)
		inline fun <reified K:Any> create(
			type: FormType? = null,
			classes: Set<String> = setOf(),
			noinline init: (OpenFormPanel<K>.() -> Unit)? = null
		) =
			OpenFormPanel(
				type = type,
				classes = classes,
				serializer = K::class.serializer()
			).apply {
				init?.invoke(this)
			}
	}
}

inline fun <reified K:Any> Container.formPanel(
	type: FormType? = null,
	classes: Set<String> = setOf(),
	noinline init: (OpenFormPanel<K>.() -> Unit)? = null
): FormPanel<K> {
	val formPanel = OpenFormPanel.create(type, classes, init)
	this.add(formPanel)
	return formPanel
}
