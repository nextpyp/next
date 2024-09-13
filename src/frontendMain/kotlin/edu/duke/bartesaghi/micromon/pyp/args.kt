package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.services.ServerVal
import edu.duke.bartesaghi.micromon.services.Services
import js.toml.TOML
import kotlinext.js.Object


val ALL_PYP_ARGS = ServerVal {
	Args.fromJson(Services.jobs.getAllArgs())
}


/**
 * Deserializes the argument values from the PYP .pyp_config.toml format
 */
actual fun ArgValuesToml.toArgValues(args: Args): ArgValues {

	val values = ArgValues(args)

	val table = TOML.parse(this)
	for (key in Object.getOwnPropertyNames(table)) {
		val arg = args.arg(key) ?: continue

		var value = table[key]

		// transform TOML types if needed
		value = when (arg.type) {

			is ArgType.TInt -> {
				when (jsTypeOf(value)) {
					// integer Number instances get read by the TOML parser as bigint for some reason
					// so convert it back to a regular Number
					"bigint" -> js("Number(value)")
					else -> value
				}
			}

			is ArgType.TFloat -> {
				when (jsTypeOf(value)) {
					// if we write a float without a fractional part to a TOML file,
					// the TOML parser will read it back in as a `bigint`,
					// so convert it back to a Number
					"bigint" -> js("Number(value)")
					else -> value
				}
			}

			is ArgType.TFloat2 -> {
				value[0] to value[1]
			}

			else -> value
		}

		// try to parse the value, if possible
		val parsedValue = try {
			arg.type.valueOf(value)
		} catch (ex: ArgCheckException) {
			console.warn("Value", value, "for PYP arg: $arg could not be parsed:", ex.msg)
			continue
		}

		values[arg] = parsedValue
	}

	return values
}
