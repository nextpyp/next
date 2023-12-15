package edu.duke.bartesaghi.micromon.services

import kotlin.reflect.KClass


@Target(AnnotationTarget.CLASS)
annotation class ExportService(
	/** should be capitalized, so it would make sense in a Python class named <name>Service */
	val name: String
)


@Target(AnnotationTarget.FUNCTION)
annotation class ExportServiceFunction
// TODO: add params here?
//   maybe need to explictly specify a python function name?


@Target(AnnotationTarget.PROPERTY)
annotation class ExportServiceProperty(
	val skip: Boolean = false
)


@Target(AnnotationTarget.PROPERTY)
annotation class ExportRealtimeService(
	/** should be capitalized, so it would make sense in a Python class named <name>RealtimeService */
	val name: String,
	val messagesC2S: Array<KClass<out RealTimeC2S>>,
	val messagesS2C: Array<KClass<out RealTimeS2C>>,
)
