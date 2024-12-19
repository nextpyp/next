package edu.duke.bartesaghi.micromon.cluster.slurm

import edu.duke.bartesaghi.micromon.Config
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.exists
import edu.duke.bartesaghi.micromon.linux.Posix
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.error.PebbleException
import io.pebbletemplates.pebble.extension.AbstractExtension
import io.pebbletemplates.pebble.extension.Filter
import io.pebbletemplates.pebble.extension.core.DefaultFilter
import io.pebbletemplates.pebble.loader.Loader
import io.pebbletemplates.pebble.template.EvaluationContext
import io.pebbletemplates.pebble.template.PebbleTemplate
import java.io.Reader
import java.io.StringWriter
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.bufferedReader
import kotlin.io.path.div


/**
 * A templating engine for SLURM sbatch scripts, powered by Pebble:
 * https://pebbletemplates.io/
 */
class TemplateEngine {

	// configure pebble
	private val pebble = PebbleEngine.Builder()
		// set an explicit loader, so we can tightly control where templates can come from (and not come from)
		.loader(object : Loader<Template.Key> {

			// ignore these
			override fun setCharset(charset: String?) {}
			override fun setPrefix(prefix: String?) {}
			override fun setSuffix(suffix: String?) {}

			override fun resolveRelativePath(relativePath: String?, anchorPath: String?) =
				// don't try to resolve relative paths
				relativePath

			override fun resourceExists(templateName: String?): Boolean {
				templateName ?: return false
				return Template.Key(templateName)
					.exists()
			}

			override fun createCacheKey(templateName: String?): Template.Key? {
				templateName ?: return null
				return Template.Key(templateName)
			}

			override fun getReader(cacheKey: Template.Key?): Reader? {
				cacheKey ?: return null
				return cacheKey
					.absPath
					?.bufferedReader()
			}
		})
		// fail early and fast if we try to print a variable in a template that doesn't exist
		.strictVariables(true)
		// Pebble's built-in escaping only works for eg. HTML,JS,JSON,CSS outputs,
		// so implement our own escaping strategy here based on POSIX shell quoting
		.addEscapingStrategy("shell") { input ->
			input?.let { Posix.quote(it) }
		}
		.defaultEscapingStrategy("shell")
		.extension(object : AbstractExtension() {

			override fun getFilters(): Map<String,Filter> = mapOf(
				"exists" to ExistsFilter()
			)
		})
		.build()

	private fun Template.Key.toTemplate(): Template? =
		if (exists()) {
			Template(pebble, this)
		} else {
			null
		}

	private fun Template.Key.toTemplateOrThrow(): Template =
		toTemplate()
			?: throw MissingTemplateException(this)

	fun template(path: String? = null): Template? =
		Template.Key(path).toTemplate()

	fun templateOrThrow(path: String? = null): Template =
		Template.Key(path).toTemplateOrThrow()
}


class Template(
	private val pebble: PebbleEngine,
	val key: Key
) {

	data class Key(val path: Path) {

		companion object {
			const val DEFAULT = "default.peb"
		}

		constructor(path: String? = null) : this(Paths.get(path ?: DEFAULT))

		val absPath: Path? get() {

			// if no templates folder is configured, then we don't have a path
			val dir = Config.instance.web.clusterTemplatesDir
				?: return null

			// path must not contain any parent segments: eg, ..
			if (path.any { it.toString() == ".." }) {
				return null
			}

			return dir / path
		}

		fun exists(): Boolean =
			absPath?.exists()
				?: false
	}


	class TemplateArgs {
		val user = HashMap<String,Any>()
		val job = HashMap<String,Any>()
	}
	val args = TemplateArgs()


	fun eval(): String {

		val writer = StringWriter()
		val context = mapOf(
			"user" to args.user,
			"job" to args.job
		)

		try {
			val compiledTemplate = pebble.getTemplate(key.path.toString())
			compiledTemplate.evaluate(writer, context)
		} catch (ex: PebbleException) {

			// format the pebble exceptions in a user-friendly way
			val out = StringBuilder()

			fun StringBuilder.print(msg: String) =
				append(msg)
			fun StringBuilder.println(line: String) {
				print(line)
				print("\n")
			}

			out.println("There was an error processing the SLURM template:")

			var e: PebbleException? = ex
			while (e != null) {

				// show this exception
				out.println(e.pebbleMessage ?: "(unknown reason)")
				if (e.fileName != null || e.lineNumber != null) {
					out.println("  at ${e.fileName ?: "?"}:${e.lineNumber ?: "?"}")
				}

				// recurse to nested causes if needed
				e = e.cause as? PebbleException
				if (e != null) {
					out.print("Caused by: ")
				}
			}

			throw ClusterJob.ValidationFailedException(out.toString())
		}

		return writer.toString()
	}
}


data class MissingTemplateException(
	val path: Path,
	val absPath: Path?
) : NoSuchElementException("""
	|No template file found:
	|           path:  $path
	|  templates dir:  ${Config.instance.web.clusterTemplatesDir}
	|       resolved:  $absPath
""".trimIndent()) {

	constructor(key: Template.Key) : this(key.path, key.absPath)
}


class ExistsFilter : DefaultFilter() {
	// NOTE: extend DefaultFilter so we can use the magic it has in strict variables mode

	override fun getArgumentNames(): MutableList<String>? =
		// no arguments
		null

	override fun apply(
		input: Any?,
		args: MutableMap<String, Any>?,
		self: PebbleTemplate?,
		context: EvaluationContext?,
		lineNumber: Int
	): Boolean =
		input != null
}
