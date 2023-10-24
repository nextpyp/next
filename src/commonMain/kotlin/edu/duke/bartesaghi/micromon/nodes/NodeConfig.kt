package edu.duke.bartesaghi.micromon.nodes


interface NodeConfig {

	class Data(
		/** Should be unique among the in or out data for this node. */
		val id: String,
		val type: Type
	) {

		val name: String get() = type.displayName

		/**
		 * All data types that can be in/out parameters of a job
		 */
		enum class Type(val displayName: String) {

			// TODO: make better comments for these types after I understand CryoEM data a little better

			/** Raw movies from the microscope */
			Movies("Movies"),

			/** Raw movies from the microscope, at multiple different tilts */
			TiltSeries("Tilt-series"),

			/** A single refinement */
			Refinement("Particles"),

			/** Multiple refinements */
			Refinements("Particles"),

			/** A single movie refinement */
			MovieRefinement("Particles"),

			/** Multiple movie refinements */
			MovieRefinements("Particles"),

			/** A single movie frame refinements */
			MovieFrameRefinement("Frames"),

			/** Mutiple movie frame refinements */
			MovieFrameRefinements("Frames"),

			/** A single movie frame refinements */
			MovieFrameAfterRefinement("Frames"),

			/** Mutiple movie frame refinements */
			MovieFrameAfterRefinements("Frames")
		}
	}

	/**
	 * Should be unique over all nodes
	 */
	val id: String

	/**
	 * The block identifier used in the pyp_config.toml file
	 */
	val configId: String

	val name: String
	val enabled: Boolean get() = true
	val hasFiles: Boolean
	val inputs: List<Data>
	val outputs: List<Data>

	/**
	 * True if only one input should be used at a time,
	 * False if all inputs are used
	 */
	val inputsExclusive: Boolean get() = true

	fun getInputOrThrow(id: String) =
		inputs.find { id == it.id }
			?: throw NoSuchElementException("no data input with id $id in node ${this.id}")

	fun getOutputOrThrow(id: String) =
		outputs.find { id == it.id }
			?: throw NoSuchElementException("no data output with id $id in node ${this.id}")

	fun findInputs(dataType: Data.Type) =
		inputs.filter { it.type == dataType }

	fun findInputs(data: Data) = findInputs(data.type)
}
