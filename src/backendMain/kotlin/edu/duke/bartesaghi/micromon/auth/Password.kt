package edu.duke.bartesaghi.micromon.auth

import de.mkammerer.argon2.Argon2Factory
import io.ktor.utils.io.core.*
import kotlin.io.use


class Password(provider: () -> Input) : AutoCloseable {

	// Current recommendations (2019-2020) say Argon2id is the most suitable hash function for passwords, see:
	// https://security.stackexchange.com/questions/193351/in-2018-what-is-the-recommended-hash-to-store-passwords-bcrypt-scrypt-argon2
	// https://medium.com/analytics-vidhya/password-hashing-pbkdf2-scrypt-bcrypt-and-argon2-e25aaf41598e
	// https://password-hashing.net/

	// And here's a good general primer on the goals of password security from ~2013.
	// The specific details and recommendations are horribly out of date now,
	// but the introduction to the general theory and thread models are still useful:
	// https://security.stackexchange.com/questions/211/how-to-securely-hash-passwords

	// and here's more general info on general login secutity:
	// https://stackoverflow.com/questions/549/the-definitive-guide-to-form-based-website-authentication#477579

	// Hold the password itself in a byte array, so it can be explicitly and immediately cleared.
	// JVM strings are immutable and are only cleared after a garbage pass, which may not happen for a long time, if at all
	// see:
	// https://security.stackexchange.com/questions/20322/why-encrypt-data-in-memory/20369#20369
	// https://www.baeldung.com/java-storing-passwords
	private var pw: ByteArray = run pw@{

		// read the password directly from the bytestream without using any String instances
		provider().use { input ->

			var buf = ByteArray(1024)
			var totalBytesRead = 0
			while (true) {

				// read the next chunk of the input
				val bytesRead = input.readAvailable(buf, totalBytesRead, buf.size - totalBytesRead)
				if (bytesRead <= 0) {

					// if we didn't read anything this time, we're done! =D
					break
				}
				totalBytesRead += bytesRead

				// if the buffer is full, expand it
				if (totalBytesRead == buf.size) {

					val newbuf = ByteArray(buf.size*2)
					System.arraycopy(buf, 0, newbuf, 0, totalBytesRead)

					// dont't forget to wipe out the old buffer before leaving it for garbage collection
					buf.fill(0)

					buf = newbuf
				}
			}

			return@pw buf
		}
	}

	override fun close() {
		// wipe the password data immediately,
		// then the garbage collector can reclaim the memory whenever it feels like it
		pw.fill(0)
	}

	fun verify(hash: String): Boolean =
		argon.verify(hash, pw)

	fun hash(): String =
		argon.hash(iterations, memoryKiB, threads, pw)

	companion object {

		// Argon2 settings

		// the "id" variant is widely believed to be more secure, see:
		// https://security.stackexchange.com/questions/193351/in-2018-what-is-the-recommended-hash-to-store-passwords-bcrypt-scrypt-argon2
		val type = Argon2Factory.Argon2Types.ARGON2id

		// the Argon2 authors recommend 16 bytes (128 bits), see:
		// https://github.com/P-H-C/phc-winner-argon2/blob/master/argon2-specs.pdf
		val saltLength = 16

		// default value for Argon2 command line utility, see:
		// https://github.com/p-h-c/phc-winner-argon2
		val hashLength = 32

		// default value for Argon2 command line utility is 3, see:
		// https://github.com/p-h-c/phc-winner-argon2
		val iterations = 10

		// default value for Argon2 command line utility is 2^12 = 4096, see:
		// https://github.com/p-h-c/phc-winner-argon2
		val memoryKiB = 16*1024

		// default value for Argon2 command line utility is 1, see:
		// https://github.com/p-h-c/phc-winner-argon2
		val threads = 2

		val argon get() =
			Argon2Factory.create(type, saltLength, hashLength)
	}
}

// utility for picking hash hardness parameters
fun main() {
	val startNs = System.nanoTime()
	val numHashes = 100
	for (i in 0 until numHashes) {
		Password.argon.hash(Password.iterations, Password.memoryKiB, Password.threads, "ilikebeerandcheese".toByteArray(Charsets.UTF_8))
	}
	val elapsedS = (System.nanoTime() - startNs).toFloat()/1e9f
	println("h/s %.2f".format(numHashes.toFloat()/elapsedS))
	// roughly 12 h/s on my laptop... should be good enough security for now
}
