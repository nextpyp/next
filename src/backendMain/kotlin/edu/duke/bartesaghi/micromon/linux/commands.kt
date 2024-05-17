package edu.duke.bartesaghi.micromon.linux


data class Command(
	val program: String,
	val args: List<String> = emptyList()
) {

	fun wrap(program: String, args: List<String>): Command =
		Command(program, args + listOf(this.program) + this.args)
}
