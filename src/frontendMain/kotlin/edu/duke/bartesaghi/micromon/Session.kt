package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.services.ProjectData
import edu.duke.bartesaghi.micromon.services.ProjectPermission
import edu.duke.bartesaghi.micromon.services.SessionData
import edu.duke.bartesaghi.micromon.services.SessionPermission
import js.cookie.Cookies
import js.decodeURIComponent


/**
 * Parses info from the nextPYP cookie used by the server for session tracking
 */
object Session {

	private var cookie: String? = null
	private var user: User.Session? = null


	fun get(): User.Session? {

		// read the cookie value
		val cookie = Cookies["nextPYP"] ?: return null

		// check the cache first
		if (this.cookie == cookie) {
			return user
		}

		// cache miss, parse the cookie to get the user session info
		// the cookie will look like, eg:
		// id=%23sjeff&name=%23sJeff&token=%23sPzPUexikH%2F...atHrOjQ%3D%3D

		var id: String? = null
		var name: String? = null
		var haspw: Boolean? = null
		var isdemo: Boolean? = null

		// parse the cookie values
		cookie.split("&")
			.forEach { keyval ->
				val (key, value) = keyval.split("=")
				when (key) {
					"id" -> id = value.decode()
					"name" -> name = value.decode()
					"haspw" -> haspw = value.decodeBoolean()
					"isdemo" -> isdemo = value.decodeBoolean()
				}
			}

		if (id == null || name == null || haspw == null || isdemo == null) {
			console.error("malformed session cookie")
			return null
		}

		val user = User.Session(id!!, name!!, null, haspw!!, isdemo!!)

		// update the cache
		this.cookie = cookie
		this.user = user

		return user
	}

	private fun String.decode(): String {

		var str = this

		// do standard URL decoding
		str = decodeURIComponent(str)

		// it doesn't get +/space for some reason, so do that manually
		str = str.replace('+', ' ')

		// ktor encodes the session object using a custom serializer
		// see: SessionSerializerReflection.serializeValue()
		// so de-serialize the bits we need here
		if (str.startsWith("#s")) {
			str = str.substring(2)
		} else if (str.startsWith("#bo")) {
			str = str.substring(3)
		}

		return str
	}

	private fun String.decodeBoolean(): Boolean =
		// the encoder uses the first letter of eg "true" or "false"
		decode() == "t"
}


fun ProjectData.isOwner(): Boolean =
	owner.id == Session.get()?.id

fun ProjectData.canWrite(): Boolean =
	ProjectPermission.Write in permissions
