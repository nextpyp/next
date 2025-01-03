package edu.duke.bartesaghi.micromon.cluster.slurm

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.cluster.ClusterJob
import edu.duke.bartesaghi.micromon.linux.Posix
import edu.duke.bartesaghi.micromon.services.TemplateData
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.error.PebbleException
import io.pebbletemplates.pebble.extension.AbstractExtension
import io.pebbletemplates.pebble.extension.Filter
import io.pebbletemplates.pebble.extension.core.DefaultFilter
import io.pebbletemplates.pebble.loader.Loader
import io.pebbletemplates.pebble.template.EvaluationContext
import io.pebbletemplates.pebble.template.PebbleTemplate
import org.tomlj.Toml
import java.io.Reader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo


/**
 * A templating engine for SLURM sbatch scripts, powered by Pebble:
 * https://pebbletemplates.io/
 */
class TemplateEngine {

	companion object {

		fun findTemplates(): List<Template> {
			val dir = Config.instance.web.clusterTemplatesDir
				?: return emptyList()
			return Files.walk(dir).use { paths ->
				paths
					.filter { !it.isDirectory() }
					.map { it.relativeTo(dir) }
					.toList()
					.mapNotNull { Template.Key(it).toTemplate() }
			}
		}
	}


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
				val template = cacheKey?.toTemplate()
					?: return null
				val (_, content) = template.parse()
				return content.reader()
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

	fun template(path: String? = null): Template? =
		Template.Key(path).toTemplate()

	fun templateOrThrow(path: String? = null): Template =
		Template.Key(path).toTemplateOrThrow()

	fun eval(template: Template): String {

		val writer = StringWriter()
		val context = mapOf(
			"user" to template.args.user,
			"job" to template.args.job
		)

		try {
			val compiledTemplate = pebble.getTemplate(template.relPath.toString())
			compiledTemplate.evaluate(writer, context)
		} catch (ex: PebbleException) {

			// format the pebble exceptions in a user-friendly way
			val out = StringBuilder()

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

		return writer.toString().trim()
	}
}


class Template(val relPath: Path, val absPath: Path) {

	data class Key(val path: Path) {

		companion object {
			const val DEFAULT = "default.peb"
		}

		constructor(path: String? = null) : this(Paths.get(path ?: DEFAULT))

		val absPath: Path? get() {

			// if no templates folder is configured, then we don't have a path
			val dir = Config.instance.web.clusterTemplatesDir
				?: return null

			// path must not be absolute
			if (path.isAbsolute) {
				return null
			}

			// path must not contain any parent segments: eg, ..
			if (path.any { it.toString() == ".." }) {
				return null
			}

			return dir / path
		}

		fun exists(): Boolean =
			absPath?.exists()
				?: false

		fun toTemplate(): Template? =
			absPath?.let { Template(this.path, it) }

		fun toTemplateOrThrow(): Template =
			toTemplate()
				?: throw MissingTemplateException(this)
	}


	class TemplateArgs {
		val user = HashMap<String,Any>()
		val job = HashMap<String,Any>()
	}
	val args = TemplateArgs()


	/**
	 * Split the template file into a preceeding "front matter" section (which is a TOML doc, delimited by boundary lines)
	 * and a proceeding "template" section (which is the pebble doc)
	 */
	fun parse(): Pair<String,String> {

		val frontMatterLines = ArrayList<String>()
		val templateLines = ArrayList<String>()

		val modeStart = 0
		val modeFrontMatter = 1
		val modeTemplate = 2
		val boundaryLine = "#---"
		var mode = modeStart

		absPath
			.readString()
			.lineSequence()
			.forEach { line ->

				when (mode) {

					modeStart -> {

						// skip lines until the first boundary
						if (line == boundaryLine) {
							mode = modeFrontMatter
						}

						// but preserve line numbers so syntax errors still make sense
						frontMatterLines.add("")
						templateLines.add("")
					}

					modeFrontMatter -> {

						// collect lines until the next boundary
						if (line == boundaryLine) {
							mode = modeTemplate
						} else {
							frontMatterLines.add(line)
						}

						// but preserve line numbers so syntax errors still make sense
						templateLines.add("")
					}

					modeTemplate -> {
						// collect all the rest of the lines
						templateLines.add(line)
					}
				}
			}

		fun List<String>.toDoc(): String =
			joinToString("\n")

		return frontMatterLines.toDoc() to templateLines.toDoc()
	}

	fun readData(): TemplateData {

		// parse the TOML from the template front matter
		val (toml, _) = parse()
		val doc = Toml.parse(toml)
		if (doc.hasErrors()) {
			throw TomlParseException("""
				|Failed to parse cluster template font matter: $absPath:
				|${doc.errors().joinToString("\n")}
			""".trimMargin())
		}

		// read the metadata from the TOML
		return TemplateData(
			path = relPath.toString(),
			title = doc.getStringOrThrow("title"),
			description = doc.getString("description")
		)
	}
}


data class MissingTemplateException(
	val relPath: Path,
	val absPath: Path?
) : NoSuchElementException("""
	|No template file found:
	|           path:  $relPath
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
