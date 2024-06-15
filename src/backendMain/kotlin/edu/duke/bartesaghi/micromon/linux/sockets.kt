package edu.duke.bartesaghi.micromon.linux

import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong


val SOCKDIR = Paths.get("/run/nextpyp/sock")


fun SocketChannel.sendFramed(data: ByteArray) {

	// send the frame size
	write(ByteBuffer.allocate(4).apply {
		putInt(data.size)
		flip()
	})

	// send the payload
	write(ByteBuffer.wrap(data))
}


private fun SocketChannel.readAll(size: Int): ByteBuffer {
	val buf = ByteBuffer.allocate(size)
	while (true) {
		val bytesRead = read(buf)
		if (bytesRead == -1) {
			// end of stream: can't read any more, so throw
			throw AsynchronousCloseException()
		}
		if (buf.position() < buf.capacity()) {
			// wait a bit for more to show up
			Thread.sleep(100)
		} else {
			break
		}
	}
	buf.flip()
	return buf
}


fun SocketChannel.recvFramed(): ByteArray {

	// read the message size
	val sizeBuf = readAll(4)
	val size = sizeBuf.getInt().toUInt().toIntOrThrow()

	// read the payload
	val buf = readAll(size)
	return buf.array()
}


fun SocketChannel.recvFramedOrNull(): ByteArray? {
	try {
		return recvFramed()
	} catch (ex: ClosedChannelException) {
		// socket closed, no more messages
		return null
	} catch (ex: AsynchronousCloseException) {
		// socket closed, no more messages
		return null
	} catch (ex: SocketException) {
		if (ex.message == "Connection reset") {
			// socket broke, no more messages
			return null
		} else {
			throw ex
		}
	}
}


fun UInt.toIntOrThrow(): Int {
	val i = toInt()
	if (i < 0) {
		throw IllegalArgumentException("Too large to fit in signed int: $this")
	}
	return i
}


class U32Counter(start: UInt = 1u) {

	private val nextVal = AtomicLong(start.toLong())

	fun next(): UInt =
		nextVal.getAndUpdate { i ->
			// just in case, roll over to 1 if we overflow a u32
			if (i >= UInt.MAX_VALUE.toLong()) {
				1
			} else {
				// otherwise, just increment like normal
				i + 1
			}
		}.toUInt()
}
