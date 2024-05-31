package edu.duke.bartesaghi.micromon.linux


data class Command(
	val program: String,
	val args: MutableList<String> = ArrayList(),
	val envvars: MutableList<EnvVar> = ArrayList()
) {

	constructor(program: String, vararg args: String) : this(program, args = args.toMutableList())


	fun wrap(program: String, args: List<String>): Command =
		Command(
			program,
			ArrayList<String>().also {
				it.addAll(args)
				it.add(this.program)
				it.addAll(this.args)
			},
			envvars
		)

	fun toShellSafeString(): String {
		val buf = StringBuilder()

		// add envvars
		for (envvar in envvars) {
			if (buf.isNotEmpty()) {
				buf.append(' ')
			}
			buf.append("export ${Posix.quote(envvar.name)}=${Posix.quote(envvar.value)};")
		}

		// add the program
		if (buf.isNotEmpty()) {
			buf.append(' ')
		}
		buf.append(Posix.quote(program))

		// add the args
		for (arg in args) {
			if (buf.isNotEmpty()) {
				buf.append(' ')
			}
			buf.append(Posix.quote(arg))
		}

		return buf.toString()
	}
}


data class EnvVar(
	val name: String,
	val value: String
) {

	fun toList() = listOf(name, value)
}
