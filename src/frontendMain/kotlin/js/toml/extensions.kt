package js.toml


object TOML {

	fun parse(toml: String): dynamic =
		js.toml.raw.TOML.parse(toml, 1.0, "\n")
}
