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


/**
 * The Python API codegen can't tell if a property has a backing field or not
 * (since the Dokka models don't seem to capture that particular detail),
 * so we have to manally annotate any properties that should not be exported.
 */
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


@Target(AnnotationTarget.CLASS)
annotation class ExportClass(
	val polymorphicSerialization: Boolean
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
	),

	@ExportPermission("session_list")
	SessionList(
		"""
			|See a list of all sessions. 
		""".trimMargin()
	),

	@ExportPermission("session_read")
	SessionRead(
		"""
			|Read-only access to individual sessions. 
		""".trimMargin()
	),

	@ExportPermission("session_write")
	SessionWrite(
		"""
			|Write access to individual sessions. 
		""".trimMargin()
	),

	@ExportPermission("session_create")
	SessionCreate(
		"""
			|Create new sessions. 
		""".trimMargin()
	),

	@ExportPermission("session_delete")
	SessionDelete(
		"""
			|Delete existing sessions. 
		""".trimMargin()
	),

	@ExportPermission("session_control")
	SessionControl(
		"""
			|Start, stop, clear, and cancel individual sessions. 
		""".trimMargin()
	),

	@ExportPermission("session_export")
	SessionExport(
		"""
			|Start and cancel session data export jobs. 
		""".trimMargin()
	),

	@ExportPermission("session_listen")
	SessionListen(
		"""
			|Listen to individual session processing in real-time. 
		""".trimMargin()
	),

	@ExportPermission("group_list")
	GroupList(
		"""
			|See a list of all groups. 
		""".trimMargin()
	)
}
