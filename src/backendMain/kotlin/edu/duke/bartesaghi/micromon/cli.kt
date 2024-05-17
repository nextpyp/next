package edu.duke.bartesaghi.micromon

import kotlin.system.exitProcess
import java.nio.file.Path


fun main(argsArray: Array<String>) {

	val args = ArrayDeque(argsArray.toList())

	// run the command
	when (val cmd = args.firstOrNull()) {
		"localdir" -> printLocalDir()
		"shareddir" -> printSharedDir()
		"binds" -> printBinds()
		"heapmib" -> printHeapMiB()
		"jmx" -> printJmx()
		"database_memgb" -> printDatabaseMemGB()
		"oomdump" -> printOomdump()
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


private fun printLocalDir() {
	println(Config.instance.web.localDir)
}

private fun printSharedDir() {
	println(Config.instance.web.sharedDir)
}

private fun printBinds() {
	val paths = ArrayList<Path>().apply {
		addAll(Config.instance.pyp.binds)
		Config.instance.slurm?.key?.let { add(it) }
		addAll(Config.instance.web.workflowDirs)
	}
	println(paths.joinToString(" ") { "--bind \"$it\"" })
}

private fun printHeapMiB() {
	println(Config.instance.web.heapMiB)
}

private fun printJmx() {
	println(if (Config.instance.web.jmx) "on" else "")
}

private fun printDatabaseMemGB() {
	println(Config.instance.web.databaseGB)
}

private fun printOomdump() {
	println(if (Config.instance.web.oomdump) "on" else "")
}
