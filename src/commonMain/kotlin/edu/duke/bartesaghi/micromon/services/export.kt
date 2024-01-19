package edu.duke.bartesaghi.micromon.services

import kotlin.reflect.KClass


/**
 * WARNING:
 *
 * Whenever you add these annotations to new source code locations,
 * or change/remove these annotations from existing source code locations,
 * carefully consider the effect of those changes on the version number of the API.
 *
 * The API version number helps API clients figure out compatibility
 */


@Target(AnnotationTarget.CLASS)
annotation class ExportService(
	/** should be capitalized, so it would make sense in a Python class named <name>Service */
	val name: String
)


@Target(AnnotationTarget.FUNCTION)
annotation class ExportServiceFunction(
	val permission: AppPermission
)


@Target(AnnotationTarget.PROPERTY)
annotation class ExportServiceProperty(
	val skip: Boolean = false
)


@Target(AnnotationTarget.PROPERTY)
annotation class ExportRealtimeService(
	/** should be capitalized, so it would make sense in a Python class named <name>RealtimeService */
	val name: String,
	val permission: AppPermission,
	val messagesC2S: Array<KClass<out RealTimeC2S>>,
	val messagesS2C: Array<KClass<out RealTimeS2C>>
)

@Target(AnnotationTarget.PROPERTY)
annotation class ExportPermission(
	val appPermissionId: String
)


enum class AppPermission(
	/** intended for users to understand */
	val description: String
) {

	@ExportPermission("open")
	Open(
		"""
			|Granted to all clients by default, regardless of app token.
			|There is no need to request this permission.
		""".trimMargin()
	),

	@ExportPermission("admin")
	Admin(
		"""
			|Use your administrator access, if you have it.
			|This permission would allow the app to, for example, take actions
			|on behalf of others users using the administrator permission on your account. 
		""".trimMargin()
	),

	@ExportPermission("project_list")
	ProjectList(
		"""
			|See a list of all of your projects. 
		""".trimMargin()
	),

	@ExportPermission("project_listen")
	ProjectListen(
		"""
			|Listen to your project and its running jobs in real-time. 
		""".trimMargin()
	)
}
