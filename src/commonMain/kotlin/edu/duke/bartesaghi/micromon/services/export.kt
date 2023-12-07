
@Target(AnnotationTarget.CLASS)
annotation class ExportService(
	/** should be capitalized, so it would make sense in a Python class named <name>Service */
	val name: String
)


@Target(AnnotationTarget.FUNCTION)
annotation class ExportServiceFunction
