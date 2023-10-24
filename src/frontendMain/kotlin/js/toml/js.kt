package js.toml.raw


/**
 * https://github.com/LongTengDao/j-toml
 * https://www.npmjs.com/package/@ltd/j-toml
 */
@JsModule("@ltd/j-toml")
@JsNonModule
external object TOML {

	fun parse(
		source: String,
		specificationVersion: Number,
		multiLineJoiner: String,
		useBigInt: Boolean? = definedExternally,
		xOptions: Any? = definedExternally
	): dynamic
}
