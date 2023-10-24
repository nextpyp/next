package edu.duke.bartesaghi.micromon

import org.slf4j.LoggerFactory


class Email(val cmd: String?) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun send(to: String, subject: String, body: String) {

		// log the email
		log.info("""
			|EMAIL:
			|        to: $to
			|   subject: $subject
			|$body
		""".trimMargin())

		// send the email if sendmail is available
		if (cmd != null) {
			val result = ProcessBuilder()
				.command(cmd, to)
				.stream {
					it.bufferedWriter().run {
						write("""
							|Subject: $subject
							|$body
							|
						""".trimMargin())
						flush()
					}
				}
				.waitFor()

			log.info("Sent email (exit code=${result.exitCode})\n" + result.console.joinToString("\n"))
		} else {
			log.info("Didn't send email. No sendmail command configured.")
		}
	}
}
