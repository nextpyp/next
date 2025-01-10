package edu.duke.bartesaghi.micromon.cluster

import edu.duke.bartesaghi.micromon.Config
import edu.duke.bartesaghi.micromon.cluster.slurm.Gres
import edu.duke.bartesaghi.micromon.linux.userprocessor.editPermissionsAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writeStringAs
import edu.duke.bartesaghi.micromon.mongo.getListOfStrings
import org.bson.Document
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission


sealed interface Commands {

	val id: String

	val arraySize: Int?
	val bundleSize: Int?

	val isArray: Boolean get() =
		arraySize != null

	val numJobs: Int get() =
		arraySize ?: 1

	fun toDoc(): Document

	/**
	 * Render the commands into a flat list for display purposes.
	 * These commands need not be runnable at all.
	 */
	fun toDisplayLists(): List<List<String>>

	/**
	 * Pick one command that somehow represents this job
	 */
	fun representativeCommand(): String

	/**
	 * Any other parameters used by the command that don't appear in the command itself
	 */
	fun params(): String?


	data class Config(
		/**
		 * There used to be a useful property here, but we removed it.
		 * Keeping this config class and its plumbing may be useful for the future though,
		 * but data classes can't be empty,
		 * so we need a dummy property until we put something else useful here again.
		 */
		val reserved: String = ""
	)

	suspend fun render(job: ClusterJob, config: Config): List<String>

	fun filesToDelete(job: ClusterJob): List<Path> =
		listOf(batchPath(job))

	companion object {

		fun fromDoc(doc: Document): Commands =
			when (val id = doc.getString("type")) {
				CommandsScript.ID -> CommandsScript.fromDoc(doc)
				CommandsGrid.ID -> CommandsGrid.fromDoc(doc)
				else -> throw NoSuchElementException("unrecognized commands type: $id")
			}

		/**
		 * Legacy, to support reading cluster jobs from the database in the old format.
		 */
		fun fromList(commands: List<*>, arraySize: Int?): Commands =
			CommandsScript(commands.map { it as String }, null, arraySize)
	}
}


private fun Commands.batchPath(job: ClusterJob) =
	ClusterJob.batchDir.resolve("commands-$id-${job.idOrThrow}.sh")

private fun batchHeader(job: ClusterJob): String =
	"""
		|#!/bin/bash
		|
		|# override SLURM's own submit dir, since SLURM won't know about the container filesystem
		|export SLURM_SUBMIT_DIR="${job.dir}"
		|
		|# change into the job folder, or abort immediately
		|# due to the way pyp works, running in the wrong directory can be incredibly destructive to any files there
		|# we should treat directory change failures as critical errors!
		|cd "${job.dir}" || exit 1
		|
	""".trimMargin()


/**
 * Run commands in SLURM where all the commands are wrapped with apptainer (or not) as a whole
 */
class CommandsScript(
	val commands: List<String>,
	val params: String?,
	/** if true, runs the script as a SLURM array job */
	override val arraySize: Int? = null,
	override val bundleSize: Int? = null
) : Commands {

	companion object {

		const val ID = "script"

		fun fromDoc(doc: Document) = CommandsScript(
			commands = doc.getListOfStrings("commands") ?: emptyList(),
			params = doc.getString("params"),
			arraySize = doc.getInteger("arraySize")
		)
	}

	override val id get() = ID

	override fun toDoc() = Document().apply {
		set("type", ID)
		set("commands", commands)
		set("params", params)
		set("arraySize", arraySize)
	}

	override fun toDisplayLists(): List<List<String>> =
		listOf(commands)

	override fun representativeCommand(): String =
		commands.firstOrNull() ?: ""

	override fun params() = params

	override suspend fun render(job: ClusterJob, config: Commands.Config): List<String> {

		// write the commands into a batch script
		val batchPath = batchPath(job)
		batchPath.writeStringAs(job.osUsername, StringBuilder().apply {

			appendLine(batchHeader(job))

			// just write all the commands in sequence
			for (command in this@CommandsScript.commands) {
				appendLine(command)
			}

		}.toString())
		batchPath.editPermissionsAs(job.osUsername) {
			add(PosixFilePermission.OWNER_EXECUTE)
		}

		val commands = ArrayList<String>()

		// get the apptainer wrapper, if needed
		val container = Container.fromId(job.containerId)
		val apptainerWrapper = if (container != null) {
			commands.addAll(container.prelaunchCommands())
			apptainerWrapper(job, container)
		} else {
			null
		}

		// apply the wraps to the script
		commands.add("\"${batchPath}\"".wrap(apptainerWrapper))

		return commands
	}
}


class CommandsGrid(
	val commands: List<List<String>>,
	override val bundleSize: Int? = null
) : Commands {

	companion object {

		const val ID = "grid"

		fun fromDoc(doc: Document) = CommandsGrid(
			commands = doc.getList("commands", List::class.java)
				?.map { list -> list.map { it as String } }
				?: emptyList()
		)
	}

	override val id get() = ID

	override val arraySize = commands.size

	override fun toDoc() = Document().apply {
		set("type", ID)
		set("commands", commands)
	}

	override fun toDisplayLists(): List<List<String>> =
		commands

	override fun representativeCommand(): String =
		commands.firstOrNull()?.firstOrNull() ?: ""

	override fun params() = null

	override suspend fun render(job: ClusterJob, config: Commands.Config): List<String> {

		val commands = ArrayList<String>()

		// get the apptainer wrapper, if needed
		val container = Container.fromId(job.containerId)
		val apptainerWrapper = if (container != null) {
			commands.addAll(container.prelaunchCommands())
			apptainerWrapper(job, container)
		} else {
			null
		}

		// write the commands into a batch script
		val batchPath = batchPath(job)
		batchPath.writeStringAs(job.osUsername, StringBuilder().apply {

			appendLine(batchHeader(job))

			for ((groupi, group) in this@CommandsGrid.commands.withIndex()) {
				val arrayId = groupi + 1
				appendLine("if [ \"\$SLURM_ARRAY_TASK_ID\" -eq \"$arrayId\" ]; then")
				for (command in group) {
					append('\t')
					appendLine(command.wrap(apptainerWrapper))
				}
				appendLine("fi")
			}

		}.toString())
		batchPath.editPermissionsAs(job.osUsername) {
			add(PosixFilePermission.OWNER_EXECUTE)
		}

		// run the script without any wrappers
		commands.add("\"${batchPath}\"")

		return commands
	}
}


/**
 * Returns a function that wraps a command within a apptainer launch command
 */
fun apptainerWrapper(job: ClusterJob, container: Container): (String) -> String {

	// build the args needed to run the apptainer container
	val apptainerArgs = mutableListOf(
		// don't automatically pull in random stuff from the user's home directory
		// this confuses the hell out of Python package lookups
		"--no-home"
	)

	// handle the container binds
	for (path in container.binds) {
		apptainerArgs.add("--bind=\"$path\"")
	}
	apptainerArgs.add("--bind=\"${Config.instance.web.sharedDir}\"")

	// set the working directory
	apptainerArgs += listOf("--pwd \"${job.dir}\"")

	// turn on NVidia support if the job requested a GPU specifically
	val requestedGpu = job.argsParsed
		.firstOrNull { (name, _) -> name == "gres" }
		?.let { (_, value) -> Gres.parseAll(value) }
		?.any { it.name == "gpu" }
		?: false
	if (requestedGpu) {
		apptainerArgs.add("--nv")
	}

	// build the apptainer command
	val prefix = mutableListOf("apptainer --quiet exec",
		apptainerArgs.joinToString(" "),
		"\"${container.sifPath}\"",
	).joinToString(" ")

	return { command ->
		"$prefix $command"
	}
}


typealias Wrapper = (String) -> String


fun String.wrap(vararg wrappers: Wrapper?): String {
	var c = this
	wrappers
		.filterNotNull()
		.forEach {
			c = it(c)
		}
	return c
}
