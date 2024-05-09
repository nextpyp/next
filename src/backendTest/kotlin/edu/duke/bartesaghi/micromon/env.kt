package edu.duke.bartesaghi.micromon

import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.spec.Spec
import kotlin.reflect.KClass


object RuntimeEnvironment {

	private fun containerName(): String? =
		System.getenv("APPTAINER_NAME")

	/**
	 * These tests should be run on the host OS, ie outside of any container
	 */
	class Host : EnabledCondition {

		override fun enabled(kclass: KClass<out Spec>): Boolean =
			containerName() == null
	}


	class Website : EnabledCondition {

		override fun enabled(kclass: KClass<out Spec>): Boolean =
			containerName() == "nextPYP.sif"
	}
}
