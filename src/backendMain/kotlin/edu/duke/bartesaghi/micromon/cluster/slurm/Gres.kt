package edu.duke.bartesaghi.micromon.cluster.slurm

import edu.duke.bartesaghi.micromon.assertEq


data class Gres(
	val name: String,
	val count: Count? = null,
	val type: String? = null
) {

	val isGpu: Boolean get() =
		name == "gpu"

	data class Count(
		val value: Int,
		val unit: CountUnit? = null
	) {

		companion object {

			fun parse(str: String): Count {

				val countStr = str
					.takeWhile { it.isDigit() }
					.takeIf { it.isNotEmpty() }
					?: throw IllegalArgumentException("unrecognized gres count: $str")
				val unitStr = str
					.substring(countStr.length)

				return Count(
					countStr.toIntOrNull()
						?: throw IllegalArgumentException("failed to parse int for gres count: $countStr"),
					unitStr
						.let { CountUnit.parse(it) }
				)
			}
		}

		fun expand(): Long =
			value*(unit?.size ?: 1L)
	}

	enum class CountUnit(val size: Long) {

		K(1024L),
		M(1024L*1024L),
		G(1024L*1024L*1024L),
		T(1024L*1024L*1024L*1024L),
		P(1024L*1024L*1024L*1024L*1024L);

		companion object {

			fun parse(str: String): CountUnit? =
				when (str.lowercase()) {
					"" -> null
					"k" -> K
					"m" -> M
					"g" -> G
					"t" -> T
					"p" -> P
					else -> throw IllegalArgumentException("unrecognized gres count unit: $str")
				}
		}
	}

	companion object {

		/**
		 * Specifies a comma-delimited list of generic consumable resources.
		 *
		 * The format for each entry in the list is "name[[:type]:count]".
		 * The name is the type of consumable resource (e.g. gpu).
		 * The type is an optional classification for the resource (e.g. a100).
		 * The count is the number of those resources with a default value of 1.
		 * The count can have a suffix of
		 *   "k" or "K" (multiple of 1024),
		 *   "m" or "M" (multiple of 1024 x 1024),
		 *   "g" or "G" (multiple of 1024 x 1024 x 1024),
		 *   "t" or "T" (multiple of 1024 x 1024 x 1024 x 1024),
		 *   "p" or "P" (multiple of 1024 x 1024 x 1024 x 1024 x 1024).
		 * The specified resources will be allocated to the job on each node.
		 * The available generic consumable resources is configurable by the system administrator.
		 * A list of available generic consumable resources will be printed and the command
		 * will exit if the option argument is "help".
		 *
		 * Examples of use include "--gres=gpu:2", "--gres=gpu:kepler:2", and "--gres=help".
		 *
		 * https://slurm.schedmd.com/sbatch.html#OPT_gres
		 */
		fun parseAll(str: String): List<Gres> {

			val parts = str.split(',', ' ')

			return if (parts.size == 1) {
				val part = parts[0]
				parse(part)
					?.let { listOf(it) }
					?: throw IllegalArgumentException("--gres value unrecognizable: $part")
			} else {
				parts.map { part ->
					parse(part)
						?: throw IllegalArgumentException("--gres element unrecognizable: $part in $str")
				}
			}
		}

		fun parse(str: String): Gres? {
			val parts = str.split(':')
			return when (parts.size) {
				1 -> Gres(parts[0])
				2 -> Gres(parts[0], Count.parse(parts[1]))
				3 -> Gres(parts[0], Count.parse(parts[2]), parts[1])
				else -> null
			}
		}
	}
}


// unit tests
fun main() {

	listOf(
		"foo" to listOf(Gres("foo")),
		"foo:1" to listOf(Gres("foo", Gres.Count(1))),
		"foo:1g" to listOf(Gres("foo", Gres.Count(1, Gres.CountUnit.G))),
		"foo:1G" to listOf(Gres("foo", Gres.Count(1, Gres.CountUnit.G))),
		"foo:bar:1P" to listOf(Gres("foo", Gres.Count(1, Gres.CountUnit.P), "bar")),
		"foo,bar" to listOf(
			Gres("foo"),
			Gres("bar")
		)
	).forEach { (str, exp) ->
		val obs = Gres.parseAll(str)
		assertEq(obs, exp, str)
	}
}
