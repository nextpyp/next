
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
