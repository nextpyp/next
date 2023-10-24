package edu.duke.bartesaghi.micromon

import kotlin.system.exitProcess
import java.nio.file.Path


fun main(argsArray: Array<String>) {

	val args = ArrayDeque(argsArray.toList())

	// read the config
	val config = Config.fromCanon()

	// run the command
	when (val cmd = args.firstOrNull()) {
		"localdir" -> printLocalDir(config)
		"shareddir" -> printSharedDir(config)
		"binds" -> printBinds(config)
		"heapmib" -> printHeapMiB(config)
		"jmx" -> printJmx(config)
		"database_memgb" -> printDatabaseMemGB(config)
		"oomdump" -> printOomdump(config)
		null -> failCmd("no comamnd")
		else -> failCmd("unrecognized command: $cmd")
	}
}

private fun fail(msg: String): Nothing {
	System.err.println(msg)
	exitProcess(1)
}

private fun failCmd(msg: String): Nothing =
	fail("$msg\nTry one of these commands: [localdir, shareddir, binds, heapmib, database_memgb, jmx]")


private fun printLocalDir(config: Config) {
	println(config.web.localDir)
}

private fun printSharedDir(config: Config) {
	println(config.web.sharedDir)
}

private fun printBinds(config: Config) {
	val paths = ArrayList<Path>().apply {
		addAll(config.pyp.binds)
		config.slurm?.key?.let { add(it) }
		addAll(config.web.workflowDirs)
	}
	println(paths.joinToString(" ") { "--bind $it" })
}

private fun printHeapMiB(config: Config) {
	println(config.web.heapMiB)
}

private fun printJmx(config: Config) {
	println(if (config.web.jmx) "on" else "")
}

private fun printDatabaseMemGB(config: Config) {
	println(config.web.databaseGB)
}

private fun printOomdump(config: Config) {
	println(if (config.web.oomdump) "on" else "")
}
