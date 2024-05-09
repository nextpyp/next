package edu.duke.bartesaghi.micromon


// TODO: convert anything using this to kotest?
fun assertEq(obs: Any?, exp: Any?, msg: String? = null) {
	if (obs != exp) {
		throw AssertionError("""
			|
			|  expected:  $exp
			|observered:  $obs
			|       msg:  ${msg ?: ""}
		""".trimMargin())
	}
}
