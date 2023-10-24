package edu.duke.bartesaghi.micromon


/**
 * Front-end code doesn't deal with paths very often,
 * so there's no standard libraries for this that I know of
 */
object Paths {

	const val separator = '/'

	fun removeTrailingSeparator(path: String): String =
		if (path.lastOrNull() == separator) {
			path.substring(0, path.length - 1)
		} else {
			path
		}

	fun prefixWithSeparator(path: String): String =
		if (path.firstOrNull() != separator) {
			separator + path
		} else {
			path
		}

	fun postfixWithSeparator(path: String): String =
		if (path.lastOrNull() != separator) {
			path + separator
		} else {
			path
		}

	fun join(parent: String, child: String): String =
		removeTrailingSeparator(parent) + prefixWithSeparator(child)

	fun join(parts: List<String>): String? =
		parts.reduceOrNull { path, part -> path / part }

	fun startsWith(parent: String, query: String): Boolean =
		query.startsWith(postfixWithSeparator(parent))

	/**
	 * Removes the last path element from the path,
	 * returning both the parent (if any) and the last path element.
	 */
	fun pop(path: String): Pair<String?,String> {

		// get the position of the last separator, if any, but not a trailing separator
		val pos = removeTrailingSeparator(path)
			.indexOfLast { it == separator }
			.takeIf { it >= 0 }
			?: return null to path

		return if (pos == 0) {
			// path is a root subfolder, eg /foo
			separator.toString() to path.substring(pos + 1)
		} else {
			path.substring(0, pos) to path.substring(pos + 1)
		}
	}

	fun filename(path: String): String =
		removeTrailingSeparator(pop(path).second)
}

operator fun String.div(child: String): String =
	Paths.join(this, child)

fun List<String>.joinPath(): String? =
	Paths.join(this)

fun String.popPath(): Pair<String?,String> =
	Paths.pop(this)


/**
 * A simple implementation of a glob matcher, so we don't have to import another JS library.
 * Only supports the * operator, for now.
 */
class GlobMatcher(val glob: String) {

	// convert it into a regex
	val regex = Regex(Regex.escape(glob).replace("\\*", ".*"))

	fun matches(q: String): Boolean =
		regex.matches(q)
}
