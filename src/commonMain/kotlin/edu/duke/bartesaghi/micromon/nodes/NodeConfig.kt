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

			/** Raw movies from the microscope */
			Movies("Movies"),

			/** Raw movies from the microscope, at multiple different tilts */
			TiltSeries("Tilt-series"),

			/** Single images created from aligning and processing the movies */
			Micrographs("Micrographs"),

			/** 3D voxel fields constructed from tilt series */
			Tomograms("Tomograms"),

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
			MovieFrameAfterRefinements("Frames"),

			/** Particle coordinates from Phoenix a parquet file */
			ParticlesParquet("Particles (Parquet)"),

			/** A trained model for picking particles */
			ParticlesModel("Particles Model"),

			/** A trained model for denoising */
			DenoisingModel("Denoising Model"),

			/** A trained model for MiLoPYP */
			MiloModel("MiLoPYP Model"),

			/** Segmentation model for open surfaces */
			SegmentationOpen("Segmentation (open)"),

			/** Segmentation model for closed surfaces */
			SegmentationClosed("Segmentation (closed)")
		}
	}

	enum class NodeType {
		SingleParticleRawData,
		TomographyRawData
	}

	enum class NodeStatus {
		/** an old, retired node: only supported on existing projects, but not new ones */
		Legacy,
		/** normal everyday nodes, ready for production use */
		Regular,
		/** a new, in-development node, not ready for general use just yet */
		Preview
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
	val type: NodeType? get() = null
	val status: NodeStatus get() = NodeStatus.Regular
	val inputs: List<Data>
	val outputs: List<Data>
	val supportsCopyData: Boolean get() = false

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
