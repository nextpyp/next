package edu.duke.bartesaghi.micromon.linux.hostprocessor

import edu.duke.bartesaghi.micromon.linux.toIntOrThrow
import java.io.DataInput
import java.io.DataOutput


fun DataOutput.writeU32(i: UInt) =
	// NOTE: writeInt() is big-endian
	writeInt(i.toInt())

fun DataInput.readU32(): UInt =
	// NOTE: readInt() is big-endian
	readInt().toUInt()


fun DataOutput.writeBytes(b: ByteArray) {
	writeU32(b.size.toUInt())
	write(b)
}

fun DataInput.readBytes(): ByteArray {
	val size = readU32().toIntOrThrow()
	val bytes = ByteArray(size)
	readFully(bytes)
	return bytes
}


fun DataOutput.writeUtf8(s: String) {
	writeBytes(s.toByteArray(Charsets.UTF_8))
}

fun DataInput.readUtf8(): String =
	readBytes().toString(Charsets.UTF_8)


fun <T> DataOutput.writeArray(items: List<T>, itemWriter: (T) -> Unit) {
	writeU32(items.size.toUInt())
	for (item in items) {
		itemWriter(item)
	}
}

fun <T> DataInput.readArray(itemReader: () -> T): List<T> {
	val size = readU32().toIntOrThrow()
	val list = ArrayList<T>(size)
	for (i in 0 until size) {
		list.add(itemReader())
	}
	return list
}


fun <T> DataOutput.writeOption(item: T?, itemWriter: (T) -> Unit) {
	if (item != null) {
		writeU32(1u)
		itemWriter(item)
	} else {
		writeU32(2u)
	}
}

fun <T> DataInput.readOption(itemReader: () -> T): T? =
	when (val signal = readU32()) {
		1u -> itemReader()
		2u -> null
		else -> throw NoSuchElementException("unrecognized option signal: $signal")
	}
