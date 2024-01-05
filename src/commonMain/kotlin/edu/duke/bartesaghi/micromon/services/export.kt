package edu.duke.bartesaghi.micromon.services

import kotlin.reflect.KClass


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
	val messagesS2C: Array<KClass<out RealTimeS2C>>,
)


enum class AppPermission(
	val appPermissionId: String,
	/** intended for users to understand */
	val description: String
) {

	Open(
		"open",
		"""
			|Granted to all clients by default, regardless of app token.
			|There is no need to request this permission.
		""".trimMargin()
	),

	Admin(
		"admin",
		"""
			|Use your administrator access, if you have it.
			|This permission would allow the app to, for example, take actions
			|on behalf of others users using the administrator permission on your account. 
		""".trimMargin()
	),

	ProjectList(
		"project_list",
		"""
			|See a list of all of your projects. 
		""".trimMargin()
	),

	ProjectListen(
		"project_listen",
		"""
			|Listen to your project and its running jobs in real-time. 
		""".trimMargin()
	);


	companion object {

		private val permissions = HashMap<String,AppPermission>()

		init {

			// build the index, make sure there are no duplicate IDs
			for (permission in values()) {
				if (permission.appPermissionId in permissions) {
					throw Error("Duplicate app permission ID: ${permission.appPermissionId}")
				}
				permissions[permission.appPermissionId] = permission
			}
		}

		operator fun get(appPermissionId: String): AppPermission? =
			permissions[appPermissionId]

		fun getOrThrow(appPermissionId: String): AppPermission =
			get(appPermissionId)
				?: throw NoSuchElementException("no app permission $appPermissionId")

		fun toDataUnknown(appPermissionId: String) =
			AppPermissionData(
				appPermissionId,
				null
			)
	}

	fun toData() = AppPermissionData(
		appPermissionId,
		description
	)
}

fun AppPermission?.toData(appPermissionId: String): AppPermissionData =
	this?.toData()
		?: AppPermission.toDataUnknown(appPermissionId)
