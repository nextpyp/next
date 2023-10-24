package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.slowIOs
import edu.duke.bartesaghi.micromon.stream
import edu.duke.bartesaghi.micromon.toPath
import kotlinx.coroutines.runBlocking
import java.nio.file.Path


object DF {

	data class Filesystem(
		val name: String,
		val type: String,
		val bytes: Long,
		val bytesUsed: Long,
		val mountedOn: Path
	) {

		val bytesFree get() = bytes - bytesUsed
		val gibFree get() = bytesFree/1024/1024/1024
	}

	suspend fun run(): List<Filesystem> = slowIOs f@{

		// run df
		val lines = ProcessBuilder()
			.command("df", "--block-size=1", "-T")
			.stream()
			.waitFor()
			.console
			.toList()

		/* we should get something like:
		Filesystem                                                   Type           1B-blocks            Used      Available Use% Mounted on
		udev                                                         devtmpfs     97055465472               0    97055465472   0% /dev
		tmpfs                                                        tmpfs        19416129536        13156352    19402973184   1% /run
		/dev/mapper/ubuntuos-root                                    xfs          40852606976     15195656192    25656950784  38% /
		tmpfs                                                        tmpfs        97080635392               0    97080635392   0% /dev/shm
		tmpfs                                                        tmpfs            5242880               0        5242880   0% /run/lock
		tmpfs                                                        tmpfs        97080635392               0    97080635392   0% /sys/fs/cgroup
		/dev/mapper/scratch-scratch                                  xfs         482943700992       515063808   482428637184   1% /scratch
		/dev/sda2                                                    ext2          1008328704       350183424      606924800  37% /boot
		/dev/sda1                                                    vfat           535805952         4603904      531202048   1% /boot/efi
		/dev/mapper/ubuntuos-srv                                     xfs           5106565120        38998016     5067567104   1% /srv
		/dev/mapper/ubuntuos-home                                    xfs           5106565120      2588979200     2517585920  51% /home
		xxxxxxxxxxxxxxxxxxxxxxxxx:xxx/xxxxxxxxxxxxx/xxxxxxxxxxxxxxxx nfs      xxxxxxxxxxxxxxx xxxxxxxxxxxxxxx xxxxxxxxxxxxxx  92% /nfs/foo
		*/

		// split the header from the filesystem entries
		val header = lines.getOrNull(0)
			?: return@f emptyList()
		val entries = lines.subList(1, lines.size)

		// get the column delimiters by finding the line offsets where all lines have a space character
		// but since there may be multiple spaces separating a column, be sure that the next character is NOT a space character for at least one line
		val offsets = ArrayList<Int>()
		for (i in header.indices) {
			if (lines.all { i < it.length && it[i] == ' ' } && lines.any { i < it.length - 1 && it[i + 1] != ' '}) {
				offsets.add(i)
			}
		}

		fun String.col(i: Int): String {

			val start = if (i == 0) {
				0
			} else {
				offsets[i-1]
			}

			val stop = if (i < offsets.size) {
				offsets[i]
			} else {
				length
			}

			return substring(start, stop).trim()
		}

		try {
			entries.map { entry ->
				Filesystem(
					name = entry.col(0),
					type = entry.col(1),
					bytes = entry.col(2).toLong(),
					bytesUsed = entry.col(3).toLong(),
					mountedOn = entry.col(6).toPath()
				)
			}
		} catch (t: Throwable) {
			throw Error("""
				|failed to parse output from DF:
				|
				|${lines.joinToString("\n")}
				|
				|Calculated column offsets: $offsets
			""".trimMargin(), t)
		}
	}
}

// for testing
fun main() {
	runBlocking {
		DF.run().forEach { println(it) }
	}
}
