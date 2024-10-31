package edu.duke.bartesaghi.micromon.pyp

import kotlinx.serialization.*
import kotlinx.serialization.json.Json


@Serializable
class Args(
	val blocks: List<Block>,
	val groups: List<ArgGroup>,
	val args: List<Arg>
) {

	fun toJson(): String =
		Json.encodeToString(this)

	companion object {

		fun fromJson(json: String): Args =
			Json.decodeFromString(json)
	}

	// build the index structures
	// normally, we'd use lazy{} here, but the javascript implementation seems buggy, so we'll just build them eagerly instead
	@Transient
	private val blockLookup: Map<String,Block> =
		blocks.associateBy { it.blockId }

	@Transient
	private val groupLookup: Map<String,ArgGroup> =
		groups.associateBy { it.groupId }

	@Transient
	private val argLookup: Map<String,Arg> =
		args.associateBy { it.fullId }

	@Transient
	private val argsByGroup: Map<String,List<Arg>> =
		args.groupBy { it.groupId }

	init {
		// initialize all the args
		for (arg in args) {
			arg.init(this)
		}
	}

	fun block(blockId: String) =
		blockLookup[blockId]

	fun blockOrThrow(blockId: String) =
		block(blockId)
			?: throw NoSuchElementException("no block $blockId")

	fun group(groupId: String) =
		groupLookup[groupId]

	fun groupOrThrow(groupId: String) =
		group(groupId)
			?: throw NoSuchElementException("no argument group $groupId")

	fun arg(fullId: String) =
		argLookup[fullId]

	fun argOrThrow(fullId: String) =
		arg(fullId)
			?: throw NoSuchElementException("no argument $fullId")

	fun arg(groupId: String, argId: String) =
		argLookup[Arg.fullId(groupId, argId)]

	fun argOrThrow(groupId: String, argId: String) =
		arg(groupId, argId)
			?: throw NoSuchElementException("no argument $groupId, $argId")

	fun args(groupId: String): List<Arg> =
		argsByGroup[groupId] ?: emptyList()

	fun args(group: ArgGroup) =
		args(group.groupId)

	fun filter(blockId: String, includeForwarded: Boolean): Args {

		val groups = blockOrThrow(blockId).getGroupIds(includeForwarded)
			.mapNotNull { group(it) }

		val args = groups.flatMap { group ->
			args(group.groupId)
				.filter { it.hidden.isVisibleInBlock(blockId) }
		}

		return Args(emptyList(), groups, args)
	}

	fun filterArgs(fullIds: List<String>): Args {

		val fullIdLookup = fullIds.toSet()

		// return just the specified args
		return Args(
			blocks = emptyList(),
			groups = emptyList(),
			args = args.filter { it.fullId in fullIdLookup }
		)
	}

	fun filterForDownstreamCopy(): Args =
		Args(
			blocks = emptyList(),
			groups = groups.toList(),
			args = args.filter { it.copyToNewBlock }
		)

	/**
	 * Removes values from `values` when:
	 *   they're the same as the values in `prev`
	 *   they're the same as the default values
	 *     except when the value in `prev` is non-default
	 * Additionally, adds explicit default values when:
	 *   arguments are present in `prev` but not `values`
	 *     ie, when the argument value was removed, explicitly set it to the default value
	 */
	fun diff(valuesToml: ArgValuesToml, prevToml: ArgValuesToml?): ArgValues {

		val values = valuesToml.toArgValues(this)
		val prev = prevToml?.toArgValues(this)
			?: ArgValues(this)

		for (arg in args) {
			if (arg in values) {

				// remove default values
				if (arg.default != null && values[arg] == arg.default.value) {

					if (arg in prev && prev[arg] != arg.default.value) {
						// unless they're overriding a previous non-default value
					} else {
						values[arg] = null
					}

				// remove values that are the same as the previous values
				} else if (arg in prev && values[arg] == prev[arg]) {
					values[arg] = null
				}

			} else if (arg in prev) {

				// value was removed this time: add back the default value
				if (arg.default != null) {
					values[arg] = arg.default
				} else {
					// no default value in this case, so send a type-dependent "reset" value
					values[arg] = ArgValueReset
				}
			}
		}

		return values
	}

	fun appendAll(args: List<Arg>): Args {
		return Args(
			blocks,
			groups,
			this.args + args
		)
	}

	override fun toString() = StringBuilder().apply {
		append("Args:\n")
		append("\tBlocks:\n")
		for (block in blocks) {
			append("\t\t$block\n")
		}
		append("\tGroups:\n")
		for (group in groups) {
			append("\t\t$group\n")
		}
		append("\tArgs:\n")
		for (arg in args) {
			append("\t\t$arg\n")
		}
	}.toString()
}


@Serializable
class Block(
	val blockId: String,
	val name: String,
	val description: String,
	val groupIds: List<String>,
	val forwardedGroupIds: List<String>,
) {

	override fun toString() = name

	fun getGroupIds(includeForwarded: Boolean): List<String> =
		if (includeForwarded) {
			groupIds + forwardedGroupIds
		} else {
			groupIds
		}
}

@Serializable
class ArgGroup(
	val groupId: String,
	val name: String,
	val description: String
) {

	override fun toString() = name
}

@Serializable
class Arg(
	val groupId: String,
	val argId: String,
	val name: String,
	val description: String,
	val type: ArgType,
	val required: Boolean = false,
	val default: ArgValue? = null,
	val input: ArgInput? = null,
	val hidden: ArgHidden = ArgHidden.none(),
	val target: ArgTarget = ArgTarget.Pyp,
	val copyToNewBlock: Boolean = true,
	val advanced: Boolean = false,
	val condition: ArgCondition? = null
) {

	companion object {

		fun fullId(groupId: String, argId: String) =
			"${groupId}_$argId"
	}

	@Transient
	val fullId: String = fullId(groupId, argId)

	fun init(args: Args) {
		default?.init(args)
	}

	val defaultOrThrow: ArgValue get() =
		default ?: throw NoSuchElementException("argument $fullId has no default value")

	override fun toString() =
		if (default != null) {
			"$name = $default"
		} else {
			name
		}
}

class ArgCheckException(val msg: String) : RuntimeException(msg)

@Serializable
sealed class ArgType {

	abstract val argTypeId: String

	/** throw an ArgCheckException if the value is not representable by this type */
	abstract fun check(value: Any?): Any?

	abstract fun valueOf(value: Any?): ArgValue?

	override fun hashCode() = argTypeId.hashCode()
	override fun equals(other: Any?) = other != null && this::class == other::class
	override fun toString() = argTypeId

	// prefix class names with T to avoid collisions with Kotlin built-in types

	// NOTE: these type names are more about mathematical type than any kind of precision/storage specifier
	// eg, a Long is a inegral type, a Double is a floating-point type, and we use the largest available primitive storage

	@Serializable
	class TBool : ArgType() {

		companion object {
			const val id = "bool"
		}

		override val argTypeId = id

		override fun check(value: Any?): Boolean? =
			when (value) {
				is Boolean? -> value
				else -> throw ArgCheckException("value $value is not a bool")
			}

		override fun valueOf(value: Any?) =
			check(value)?.let { ArgValue.VBool(it) }
	}

	@Serializable
	class TInt : ArgType() {

		companion object {
			const val id = "int"
		}

		override val argTypeId = id

		override fun check(value: Any?): Long? =
			when (value) {
				is Long? -> value
				is Int? -> value?.toLong()
				else -> throw ArgCheckException("value $value is not an Int or a Long")
			}

		override fun valueOf(value: Any?) =
			check(value)?.let { ArgValue.VInt(it) }
	}

	@Serializable
	class TFloat : ArgType() {

		companion object {
			const val id = "float"
			val instance = TFloat()
		}

		override val argTypeId = id

		override fun check(value: Any?): Double? =
			when (value) {
				is Double? -> value
				is Float? -> value?.toDouble()
				else -> throw ArgCheckException("value $value is not a Float or a Double")
			}

		override fun valueOf(value: Any?) =
			check(value)?.let { ArgValue.VFloat(it) }
	}

	@Serializable
	class TFloat2 : ArgType() {

		companion object {
			const val id = "float2"
		}

		override val argTypeId = id

		override fun check(value: Any?): Pair<Double,Double>? =
			when (value) {
				null -> null
				is Pair<*,*> -> {
					val x = TFloat.instance.check(value.first) ?: throw ArgCheckException("x can't be null")
					val y = TFloat.instance.check(value.second) ?: throw ArgCheckException("y can't be null")
					x to y
				}
				else -> throw ArgCheckException("value $value is not a Pair")
			}

		override fun valueOf(value: Any?) =
			check(value)?.let {
				ArgValue.VFloat2(it)
			}
	}

	@Serializable
	class TStr : ArgType() {

		companion object {
			const val id = "str"
			val instance = TStr()
		}

		override val argTypeId = id

		override fun check(value: Any?): String? =
			when (value) {
				is String? -> value
				else -> throw ArgCheckException("value $value is not a String")
			}

		override fun valueOf(value: Any?) =
			check(value)?.let { ArgValue.VStr(it) }
	}

	@Serializable
	class TEnum(val values: List<Value>) : ArgType() {

		init {
			// make sure all the value ids are unique
			val ids = values.map { it.id }
			if (ids.size != ids.toSet().size) {
				throw IllegalArgumentException("all enum values must be unique: $ids")
			}
		}

		@Serializable
		data class Value(
			val id: String,
			val name: String
		) {

			// don't allow blank ids because that will mess up null encoding for enums
			init {
				if (id.isBlank()) {
					throw IllegalArgumentException("enum IDs cannot be blank")
				}
			}
		}

		companion object {
			const val id = "enum"
		}

		override val argTypeId: String = id

		override fun check(value: Any?): String? =
			TStr.instance.check(value).also {
				if (value != null && values.none { it.id == value }) {
					throw ArgCheckException("enum value \"$value\" not one of ${values.map { it.id }}")
				}
			}

		override fun valueOf(value: Any?) =
			check(value)?.let { ArgValue.VEnum(it) }

		override fun hashCode() =
			values.hashCode()

		override fun equals(other: Any?) =
			other is TEnum && this.values == other.values
	}

	@Serializable
	class TPath(
		val kind: Kind,
		val glob: Boolean
	) : ArgType() {

		// NOTE: don't rename `kind` to `type`, that apparently breaks JSON serialization

		enum class Kind(val id: String) {

			Files("files"),
			Folders("folders");

			companion object {

				operator fun get(id: String) =
					values().find { it.id == id }
						?: throw NoSuchElementException("no path kind found for id=$id")
			}
		}

		enum class Count(val id: String) {

			One("one"),
			Many("many");

			companion object {

				operator fun get(id: String) =
					 values().find { it.id == id }
						?: throw NoSuchElementException("no count found for id=$id")
			}
		}


		companion object {
			const val id = "path"
		}

		override val argTypeId = id

		override fun check(value: Any?): String? =
			when (value) {
				is String? -> value
				else -> throw ArgCheckException("value $value is not a String")
			}

		override fun valueOf(value: Any?) =
			check(value)
				?.let { ArgValue.VPath(it) }
	}
}

@Serializable
sealed class ArgValue {

	// prefix class names with V to avoid collisions with Kotlin built-in types

	// NOTE: these type names are more about mathematical type than any kind of precision/storage specifier
	// eg, a Long is a inegral type, a Double is a floating-point type, and we use the largest available primitive storage

	abstract val value: Any

	open fun init(args: Args) {}

	// force subclasses to define these, for correctness
	abstract override fun hashCode(): Int
	abstract override fun equals(other: Any?): Boolean
	abstract override fun toString(): String

	@Serializable
	class VBool(override val value: Boolean) : ArgValue() {
		override fun hashCode() = value.hashCode()
		override fun equals(other: Any?) =
			(other is VBool && this.value == other.value)
			|| (other is VRef && other.sourceOrThrow == this)
		override fun toString() = value.toString()
	}

	@Serializable
	class VInt(override val value: Long) : ArgValue() {
		override fun hashCode() = value.hashCode()
		override fun equals(other: Any?) =
			(other is VInt && this.value == other.value)
			|| (other is VRef && other.sourceOrThrow == this)
		override fun toString() = value.toString()
	}

	@Serializable
	class VFloat(override val value: Double) : ArgValue() {
		override fun hashCode() = value.hashCode()
		override fun equals(other: Any?) =
			(other is VFloat && this.value == other.value)
			|| (other is VRef && other.sourceOrThrow == this)
		override fun toString() = value.toString()
	}

	@Serializable
	class VFloat2(override val value: Pair<Double,Double>) : ArgValue() {
		override fun hashCode() = value.hashCode()
		override fun equals(other: Any?) =
			(other is VFloat2 && this.value == other.value)
			|| (other is VRef && other.sourceOrThrow == this)
		override fun toString() = value.toString()
	}

	@Serializable
	class VStr(override val value: String) : ArgValue() {
		override fun hashCode() = value.hashCode()
		override fun equals(other: Any?) =
			(other is VStr && this.value == other.value)
			|| (other is VRef && other.sourceOrThrow == this)
		override fun toString() = value
	}

	@Serializable
	class VEnum(override val value: String) : ArgValue() {
		override fun hashCode() = value.hashCode()
		override fun equals(other: Any?) =
			(other is VEnum && this.value == other.value)
			|| (other is VRef && other.sourceOrThrow == this)
		override fun toString() = value
	}

	@Serializable
	class VPath(override val value: String) : ArgValue() {

		init {
			// just in case
			if (value.isEmpty()) {
				throw IllegalArgumentException("empty path lists are not allowed, use null values instead")
			}
		}

		override fun hashCode() = value.hashCode()
		override fun equals(other: Any?) =
			(other is VPath && this.value == other.value)
			|| (other is VRef && other.sourceOrThrow == this)
		override fun toString() = value
	}

	@Serializable
	class VRef(val srcFullId: String, val dstFullId: String) : ArgValue() {

		override val value get() = sourceOrThrow.value

		@Transient
		var sourceArg: Arg? = null
			private set

		val sourceArgOrThrow: Arg get() =
			sourceArg
				?: throw NoSuchElementException("value reference has no source arg")

		@Transient
		var source: ArgValue? = null
			private set

		val sourceOrThrow: ArgValue get() =
			source
				?: throw NoSuchElementException("value reference has no source")

		override fun init(args: Args) {

			// bind to the source arg
			val src = args.arg(srcFullId)
				?: throw IllegalArgumentException("referenced argument $srcFullId not found, referenced by argument $dstFullId")
			val dst = args.arg(dstFullId)
				?: throw IllegalArgumentException("self not found: $dstFullId")
			if (src.type != dst.type) {
				throw IllegalArgumentException("source type ($srcFullId=${src.type}) doesn't match destination type ($dstFullId=${dst.type})")
			}
			sourceArg = src
			source = src.default
				?: throw IllegalArgumentException("source type ($srcFullId=${src.type}) doesn't have a default value")
		}

		override fun hashCode() = sourceOrThrow.hashCode()
		override fun equals(other: Any?) =
			this.sourceOrThrow == if (other is VRef) {
				other.sourceOrThrow
			} else {
				other
			}
		override fun toString() = sourceOrThrow.toString()
	}
}


class ArgValues(val args: Args) {

	class MissingValueException(arg: Arg) : NoSuchElementException("missing value: ${arg.fullId}")

	private val values = HashMap<String,Any?>()

	operator fun contains(arg: Arg): Boolean =
		arg.fullId in values

	operator fun get(arg: Arg): Any? =
		values[arg.fullId]

	fun getOrThrow(arg: Arg): Any =
		get(arg) ?: throw MissingValueException(arg)

	fun getOrDefault(arg: Arg): Any {
		get(arg)
			?.let { return it }
		val default = arg.default
			?: throw NoSuchElementException("can't get default value, argument $arg has none")
		return default.value
	}

	operator fun get(groupId: String, argId: String): Any? =
		get(args.argOrThrow(groupId, argId))

	operator fun set(arg: Arg, value: Any?) {
		if (value != null) {
			if (value == ArgValueReset) {
				// skip runtime type checking for sentinel values
				values[arg.fullId] = value
			} else {
				// enforce type safety at runtime
				values[arg.fullId] = arg.type.check(value)
			}
		} else {
			values.remove(arg.fullId)
		}
	}

	operator fun set(arg: Arg, value: ArgValue?) =
		set(arg, value?.value)

	operator fun set(groupId: String, argId: String, value: Any?) =
		set(args.argOrThrow(groupId, argId), value)

	fun clear() {
		values.clear()
	}

	fun setAll(other: ArgValues) {
		this.values.putAll(other.values)
	}

	fun setAll(toml: ArgValuesToml) {
		setAll(toml.toArgValues(args))
	}

	override fun toString() = StringBuilder().apply {
		append("ArgValues[numArgs=${args.args.size}] {\n")
		for (arg in args.args) {
			val value = get(arg)
				?: continue
			append("\t${arg.fullId} = $value\n")
		}
		append("}")
	}.toString()

	/**
	 * Serializes the argument values into the PYP .pyp_config.toml format
	 */
	fun toToml(): ArgValuesToml =
		StringBuilder().apply {
			for (arg in args.args) {
				val value = get(arg) ?: continue

				append(arg.fullId)
				append(" = ")
				append(when (arg.type) {
					is ArgType.TBool -> arg.type.check(value)!!
					is ArgType.TInt -> arg.type.check(value)!!
					is ArgType.TFloat -> arg.type.check(value)!!
					is ArgType.TFloat2 -> arg.type.check(value)!!.let { (x, y) -> "[$x, $y]" }
					is ArgType.TStr -> arg.type.check(value)!!.quote()
					is ArgType.TEnum -> arg.type.check(value)!!.quote()
					is ArgType.TPath -> arg.type.check(value)!!.quote()
				})
				append("\n")
			}
		}.toString()

	companion object {
		// make a companion so we can extend it
	}
}

private fun String.quote() = "\"$this\""


/**
 * Signals that this string is actually a TOML-serialized instance of ArgValues
 */
typealias ArgValuesToml = String

/**
 * De-serialize the ArgValues instance.
 */
expect fun ArgValuesToml.toArgValues(args: Args): ArgValues


fun ArgValuesToml.filterForDownstreamCopy(args: Args): ArgValuesToml =
	toArgValues(args.filterForDownstreamCopy())
		.toToml()


/** a sentinel value that tells the pyp command-line formatter to ask pyp to reset this arg */
object ArgValueReset {
	override fun toString() = "<ArgValueReset>"
}


@Serializable
sealed class ArgInput {

	abstract val argInputId: String

	/**
	 * Helps the user pick a parameter file for 3D refinement from previous job outputs
	 */
	@Serializable
	class ParFile : ArgInput() {

		companion object {
			const val id = "parfile"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick a parameter file for 3D refinement from previous job outputs
	 */
	@Serializable
	class StarFile : ArgInput() {

		companion object {
			const val id = "starfile"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick a parameter file for 3D refinement from previous job outputs
	 */
	@Serializable
	class ParquetFile : ArgInput() {

		companion object {
			const val id = "parquetfile"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick a parameter file for 3D refinement from previous job outputs
	 */
	@Serializable
	class TxtFile : ArgInput() {

		companion object {
			const val id = "txtfile"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick an initial model for 3D refinement from previous job outputs
	 */
	@Serializable
	class InitialModel : ArgInput() {

		companion object {
			const val id = "initialModel"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick a half map from previous 3D refinement job
	 */
	@Serializable
	class HalfMap : ArgInput() {

		companion object {
			const val id = "halfMap"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick an initial model for 3D refinement from previous job outputs
	 */
	@Serializable
	class TrainedModel2D : ArgInput() {

		companion object {
			const val id = "trainedModel2D"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick an initial model for 3D refinement from previous job outputs
	 */
	@Serializable
	class TopazTrainedModel : ArgInput() {

		companion object {
			const val id = "topazTrainedModel"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick an initial model for 3D refinement from previous job outputs
	 */
	@Serializable
	class IsonetTrainedModel : ArgInput() {

		companion object {
			const val id = "isonetTrainedModel"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick an initial model for 3D refinement from previous job outputs
	 */
	@Serializable
	class TrainedModel3D : ArgInput() {

		companion object {
			const val id = "trainedModel3D"
		}

		override val argInputId = id
	}

	/**
	 * Helps the user pick a cluster queue
	 */
	@Serializable
	class ClusterQueue(val group: Group) : ArgInput() {

		@Serializable
		enum class Group(val id: String) {

			Cpu("cpu"),
			Gpu("gpu");

			companion object {
				operator fun get(name: String): Group? =
					values()
						.find { it.id == name.lowercase() }
			}
		}

		companion object {
			// NOTE: called a "slurmQueue" for historical reasons
			// to change it here would also require a change to pyp
			const val id = "slurmQueue"
		}

		override val argInputId = id
	}
}


/**
 * Tracks which blocks the argument is hidden from.
 * Empty list for hidden from no blocks.
 * Null list for hidden from all blocks.
 *
 * "Hidden" here means the argument will not be shown in forms,
 * and the argument will not be included in block arg values.
 */
@Serializable
class ArgHidden private constructor(val blockIds: List<String>?) {

	fun isVisibleInBlock(blockId: String): Boolean =
		blockIds != null && blockId !in blockIds

	companion object {

		fun none() = ArgHidden(emptyList())

		fun all() = ArgHidden(null)

		fun some(blockIds: List<String>) = ArgHidden(blockIds)
	}
}


@Serializable
enum class ArgTarget {
	/** arguments that should be sent to pyp via the command line */
	Pyp,
	/** arguments that should be intercepted by micromon and not passed to the pyp command line */
	Micromon
}


@Serializable
class ArgCondition(
	val argId: String,
	val values: List<String>
)
