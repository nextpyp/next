@file:JvmName("ArgsBackendKt") // all of a sudden this clashes with the common/ArgsKt now

package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.*
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlPosition
import org.tomlj.TomlTable


fun Args.Companion.fromToml(toml: String): Args {

	// parse the TOML
	val doc = Toml.parse(toml)
	if (doc.hasErrors()) {
		throw TomlParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
	}

	val blocks = ArrayList<Block>()
	val groups = ArrayList<ArgGroup>()
	val args = ArrayList<Arg>()

	// read the blcoks
	val blocksTable = doc.getTableOrThrow("blocks")
	for (blockId in blocksTable.keySetInOrder()) {

		// ignore keys starting with _, they're not blocks
		if (blockId[0] == '_') {
			continue
		}

		val blockPos = blocksTable.inputPositionOf(blockId)
		val blockTable = blocksTable.getTableOrThrow(blockId)

		val groupIds = blockTable.getArrayOrThrow("tabs").let { array ->
			(0 until array.size()).map { i ->
				array.getString(i)
			}
		}

		val forwardedGroupIds = blockTable.getArray("forwarded_tabs")
			?.let { array ->
				(0 until array.size()).map { i ->
					val id = array.getString(i)
					if (id in groupIds) {
						throw TomlParseException("forwarded tab shouldn't also be a regular tab: $id", array.inputPositionOf(i))
					}
					id
				}
			}
			?: emptyList()

		blocks.add(Block(
			blockId,
			name = blockTable.getStringOrThrow("name", blockPos),
			description = blockTable.getStringOrThrow("description", blockPos),
			groupIds = groupIds,
			forwardedGroupIds = forwardedGroupIds
		))
	}

	// for each group ...
	val tabsTable = doc.getTableOrThrow("tabs")
	for (groupId in tabsTable.keySetInOrder()) {

		// ignore keys starting with _, they're not tabs
		if (groupId[0] == '_') {
			continue
		}

		val groupPos = tabsTable.inputPositionOf(groupId)
		val groupTable = tabsTable.getTableOrThrow(groupId, groupPos)

		groups.add(ArgGroup(
			groupId,
			groupTable.getStringOrThrow("_name", groupPos),
			groupTable.getStringOrThrow("_description", groupPos)
		))

		// for each arg in the group ...
		for (argId in groupTable.keySetInOrder()) {

			// skip group properties
			if (argId[0] == '_') {
				continue
			}

			val argPos = groupTable.inputPositionOf(argId)
			val argTable = groupTable.getTableOrThrow(argId, argPos)

			val type = argTable.getTypeOrThrow("type", argPos)
			args.add(Arg(
				groupId,
				argId,
				name = argTable.getStringOrThrow("name", argPos),
				description = argTable.getStringOrThrow("description", argPos),
				type = type,
				required = argTable.getBoolean("required") ?: false,
				default = try {
					argTable.getValue("default", groupId, argId, type)
				} catch (t: Throwable) {
					// add context to the error
					throw Error("Failed to read value at tabs.${groupId}.${argId}.default", t)
				},
				input = argTable.getInput("input"),
				hidden = argTable.getArgHidden("hidden"),
				copyToNewBlock = argTable.getBoolean("copyToNewBlock") ?: true,
				advanced = argTable.getBoolean("advanced") ?: false,
				condition = argTable.getCondition("condition")
			))
		}

		// check arg condition parents
		run {
			val errors = ArrayList<String>()
			for (arg in args.filter { it.groupId == groupId }) {
				val conditionArgId = arg.condition?.argId
					?: continue
				if (args.none { it.groupId == groupId && it.argId == conditionArgId }) {
					errors.add("Argument $groupId.$conditionArgId referenced in condition of $groupId.${arg.argId} was not found")
				}
			}
			if (errors.isNotEmpty()) {
				throw NoSuchElementException(errors.joinToString("\n"))
			}
		}
	}

	return Args(blocks, groups, args)
}


fun TomlTable.getTypeOrThrow(key: String, pos: TomlPosition?): ArgType {

	return when (val type = getString(key)) {
		ArgType.TBool.id -> ArgType.TBool()
		ArgType.TInt.id -> ArgType.TInt()
		ArgType.TFloat.id -> ArgType.TFloat()
		ArgType.TFloat2.id -> ArgType.TFloat2()
		ArgType.TStr.id -> ArgType.TStr()
		ArgType.TEnum.id -> {
			val enum = getTable("enum") ?: throw TomlParseException("enum type specified, but no enum value given")
			ArgType.TEnum(enum.keySetInOrder().map { valId ->
				ArgType.TEnum.Value(valId, enum.getStringOrThrow(valId, pos))
			})
		}
		ArgType.TPath.id -> {
			val path = getTable("path") ?: throw TomlParseException("path type specified, but no path value given")
			ArgType.TPath(
				kind = ArgType.TPath.Kind[path.getStringOrThrow("type", pos)],
				glob = path.getBoolean("glob") ?: false
			)
		}
		else -> throw TomlParseException("unrecognized type: $type", pos)
	}
}

fun TomlTable.getValue(key: String, groupId: String, dstArgId: String, type: ArgType): ArgValue? {

	val pos = inputPositionOf(key)
	val value = get(key) ?: return null

	fun throwIt(): Nothing {
		throw TomlParseException("value $value doesn't match type ${type.argTypeId}", pos)
	}

	// check for reference values
	if (value is TomlTable) {
		val srcArgId = value.getString("ref")
		if (srcArgId != null) {
			return ArgValue.VRef(
				Arg.fullId(groupId, srcArgId),
				Arg.fullId(groupId, dstArgId)
			)
		}
	}

	fun Any.parseFloat() =
		when (this) {
			is Double -> this
			is Long -> this.toDouble()
			else -> throwIt()
		}

	// check for literal values
	return when (type) {
		is ArgType.TBool -> ArgValue.VBool(value as? Boolean ?: throwIt())
		is ArgType.TInt -> ArgValue.VInt(value as? Long ?: throwIt())
		is ArgType.TFloat -> ArgValue.VFloat(value.parseFloat())
		is ArgType.TFloat2 -> {
			val valArr = value as? TomlArray ?: throwIt()
			val x = valArr.get(0).parseFloat()
			val y = valArr.get(1).parseFloat()
			ArgValue.VFloat2(x to y)
		}
		is ArgType.TStr -> ArgValue.VStr(value as? String ?: throwIt())
		is ArgType.TEnum -> {
			val valStr = value as? String ?: throwIt()
			if (type.values.none { it.id == valStr }) {
				throw TomlParseException("value $type not present in enum ${type.values.map { it.id }}", pos)
			}
			ArgValue.VEnum(valStr)
		}
		is ArgType.TPath -> ArgValue.VPath(value as? String ?: throwIt())
	}
}

fun TomlTable.getInput(key: String): ArgInput? {

	val pos = inputPositionOf(key)
	val inputTable = getTable(key) ?: return null

	return when (val inputTypeId = inputTable.getStringOrThrow("type", pos)) {

		ArgInput.ParFile.id -> ArgInput.ParFile()
		ArgInput.TxtFile.id -> ArgInput.TxtFile()
		ArgInput.InitialModel.id -> ArgInput.InitialModel()
		ArgInput.HalfMap.id -> ArgInput.HalfMap()
		ArgInput.TopazTrainedModel.id -> ArgInput.TopazTrainedModel()
		ArgInput.IsonetTrainedModel.id -> ArgInput.IsonetTrainedModel()
		ArgInput.TrainedModel2D.id -> ArgInput.TrainedModel2D()
		ArgInput.TrainedModel3D.id -> ArgInput.TrainedModel3D()

		ArgInput.ClusterQueue.id -> {
			val groupStr = inputTable.getStringOrThrow("group")
			val group = ArgInput.ClusterQueue.Group[groupStr]
				?: throw TomlParseException("unrecognized SLURM queue group: $groupStr, try one of ${ArgInput.ClusterQueue.Group.values().map { it.id }}")
			ArgInput.ClusterQueue(group)
		}

		else -> throw TomlParseException("unrecognized input type: $inputTypeId", pos)
	}
}

fun TomlTable.getArgHidden(key: String): ArgHidden {

	val pos = inputPositionOf(key)
	val value = get(key) ?: return ArgHidden.none()

	return when (value) {

		true -> ArgHidden.all()

		is TomlArray -> {
			val blockIds = value.toList()
				.map { it as? String ?: throw TomlParseException("block name not a string: $it", pos) }
			ArgHidden.some(blockIds)
		}

		else -> throw TomlParseException("unrecognized hidden value: $value", pos)
	}
}

fun TomlTable.getCondition(key: String): ArgCondition? {

	val pos = inputPositionOf(key)
	val value = get(key)
		?: return null

	fun adapt(v: Any?): String? =
		when (v) {
			is String -> v
			is Int,
			is Long,
			is Float,
			is Double,
			is Boolean -> v.toString()
			else -> null
		}

	return when (value) {

		is TomlTable -> ArgCondition(
			value.getStringOrThrow("arg", pos),
			when (val v = value.get("value")) {

				null -> throw TomlParseException("condition has no value", pos)

				is TomlArray -> v.indices.map {
					adapt(v.get(it))
						?: throw TomlParseException("condition value was not recognized: try a string, int, float, or boolean", pos)
				}

				else -> listOf(
					adapt(v)
						?: throw TomlParseException("condition value was not recognized: try a string, int, float, boolean, or an array of them", pos)
				)
			}
		)

		else -> throw TomlParseException("unrecognized conditionl value: $value", pos)
	}
}


/**
 * translates argument values into a command line suitable for PYP programs
 */
fun ArgValues.toPypCLI(): List<String> {
	val out = ArrayList<String>()
	for (arg in args.args) {

		// only output arguments intended for pyp
		if (arg.target != ArgTarget.Pyp) {
			continue
		}

		val id = arg.fullId

		// handle reset sentinel values
		if (this[arg] == ArgValueReset) {
			when (arg.type) {

				// for string-like values (including paths), send an empty string
				is ArgType.TStr,
				is ArgType.TPath -> out.add("-$id \"\"")

				// no special reset values exist for other arg types
				else -> {
					Backend.log.warn("""
						|ArgValueReset sentinel value encountered for pyp argument $id,
						|but arg type ${arg.type.argTypeId} has no emtpy value.
						|Omitting argument from pyp command-line.
					""".trimMargin())
				}
			}
			continue
		}

		val value = arg.type.valueOf(this[arg]) ?: continue
		when (value) {
			is ArgValue.VBool ->
				when (value.value) {
					true -> out.add("-$id")
					false -> out.add("-no-$id")
				}
			is ArgValue.VInt -> out.add("-$id ${value.value}")
			is ArgValue.VFloat -> out.add("-$id ${value.value}")
			is ArgValue.VFloat2 -> out.add("-$id (${value.value.first},${value.value.second})")
			is ArgValue.VStr -> out.add("-$id ${value.value.quote()}")
			is ArgValue.VEnum -> out.add("-$id ${value.value}")
			is ArgValue.VPath -> out.add("-$id ${value.value.quote()}")
			is ArgValue.VRef -> throw IllegalArgumentException("references are not valid values for PYP command line")
		}
	}
	return out
}


/**
 * Deserializes the argument values from the PYP .pyp_config.toml format
 */
actual fun ArgValuesToml.toArgValues(args: Args): ArgValues {

	val values = ArgValues(args)

	val doc = Toml.parse(this)
	if (doc.hasErrors()) {
		throw TomlParseException("TOML parsing failure:\n${doc.errors().joinToString("\n")}")
	}

	val argErrors = StringBuilder()

	for (key in doc.keySetInOrder()) {
		val arg = args.arg(key) ?: continue

		val value = doc.get(key).translateTomlValueForReading(arg)

		try {
			values[arg] = arg.type.valueOf(value)
		} catch (ex: ArgCheckException) {
			argErrors.append("\targ ${arg.fullId} value $value: ${ex.message}\n")
		}
	}

	if (argErrors.isNotEmpty()) {
		throw ArgCheckException("Errors occurred while parsing arg values:\n$argErrors")
	}

	return values
}

fun Any?.translateTomlValueForReading(arg: Arg): Any? =
	when (arg.type) {

		is ArgType.TFloat -> {
			when (this) {
				// if we write a float without a fractional part to a TOML file,
				// the TOML parser will read it back in as an Int or a Long,
				// so convert it back to Double
				is Int -> toDouble()
				is Long -> toDouble()
				else -> this
			}
		}

		is ArgType.TFloat2 -> {
			val array = this as TomlArray
			array[0] to array[1]
		}

		else -> this
	}



/** some informal tests for the PYP args system */
fun main() {

	val toml = """
		|[blocks.b1]
		|name = "Block 1"
		|description = "the first block"
		|tabs = ["g1"]
		|
		|[tabs.g1]
		|_name = "Group1"
		|_description = "group 1"
		|
		|[tabs.g1.a1]
		|name = "Bool"
		|description = "arg 1"
		|type = "bool"
		|default = false
		|
		|[tabs.g1.a2]
		|name = "Int"
		|description = "arg 2"
		|type = "int"
		|default = 5
		|
		|[tabs.g1.a3]
		|name = "Float"
		|description = "arg 3"
		|type = "float"
		|default = 4.2
		|
		|[tabs.g1.a4]
		|name = "String"
		|description = "arg 4"
		|type = "str"
		|default = "hello world"
		|
		|[tabs.g1.a5]
		|name = "Enum"
		|description = "arg 5"
		|type = "enum"
		|enum = { a="A", b="B", c="C" }
		|default = "a"
		|
		|[tabs.g1.a6]
		|name = "Float Pair"
		|description = "arg 6"
		|type = "float2"
		|default = [1.2, 3.4]
		|
		|[tabs.g1.a7]
		|name =  "Path"
		|description = "arg 7"
		|type = "path"
		|path = { type = "files" }
		|
		|
		|[tabs.g2]
		|_name = "Group 2"
		|_description = "group 2"
		|
		|[tabs.g2.a6]
		|name = "Nodefault"
		|description = "arg 6"
		|type = "str"
		|
		|
		|[tabs.g3]
		|_name = "Refs"
		|_description = "refs"
		|
		|[tabs.g3.a7]
		|name = "Source"
		|description = "source"
		|type = "int"
		|default = 7
		|
		|[tabs.g3.a8]
		|name = "Dest"
		|description = "destination"
		|type = "int"
		|default = { ref="a7" }
		|
		|[tabs.g3.a9]
		|name = "Hidden"
		|description = "hidden argument"
		|type = "bool"
		|default = false
		|hidden = true
		|
		|[tabs.g3.a10]
		|name = "Advanced"
		|description = "advanced argument"
		|type = "bool"
		|default = false
		|advanced = true
		|
	""".trimMargin()
	val args = Args.fromToml(toml)

	println(args)
	val json = args.toJson()
	println(json)
	println(Args.fromJson(json))

	val values = ArgValues(args)
	values["g1", "a1"] = true
	values["g1", "a2"] = 6
	values["g1", "a3"] = 7.6
	values["g1", "a4"] = "foobar"
	values["g1", "a5"] = "c"
	values["g1", "a6"] = 5.6 to 7.8
	values["g1", "a7"] = "/foo/bar"
	println(values)

	val pypconfig = values.toToml()
	println(pypconfig)
	println(pypconfig.toArgValues(args))
}
