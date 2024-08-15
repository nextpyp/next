package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.services.ParticlesList
import kotlinx.serialization.Serializable


// all the pyp values that are used directly by the Kotlin code should have identifiers the compiler can check
// hopefully to cut down on simple name drifting bugs, eg if the pyp args get renamed or something like that
// that way, we keep the sting names in one canonical place

// the ___OrThrow variants are useful for reading .pyp_config files, when all the values are guaraneteed to be available


val Args.dataPath: Arg
	get() = argOrThrow("data", "path")
val ArgValues.dataPath: String?
	get() = get(args.dataPath) as String?
val ArgValues.dataPathOrThrow: String
	get() = getOrThrow(args.dataPath) as String

val Args.dataParent: Arg
	get() = argOrThrow("data", "parent")
var ArgValues.dataParent: String?
	get() = get(args.dataParent) as String?
	set(value) { set(args.dataParent, value) }

val Args.dataMode: Arg
	get() = argOrThrow("data", "mode")
var ArgValues.dataMode: String?
	get() = get(args.dataMode) as String?
	set(value) { set(args.dataMode, value) }

val Args.dataImport: Arg
	get() = argOrThrow("data", "import")
var ArgValues.dataImport: Boolean?
	get() = get(args.dataImport) as Boolean?
	set(value) { set(args.dataImport, value) }

val Args.extractFmt: Arg
	get() = argOrThrow("extract", "fmt")
var ArgValues.extractFmt: String?
	get() = get(args.extractFmt) as String?
	set(value) { set(args.extractFmt, value) }

val Args.extractBin: Arg
	get() = argOrThrow("extract", "bin")
var ArgValues.extractBin: Long?
	get() = get(args.extractBin) as Long?
	set(value) { set(args.extractBin, value) }

val Args.extractCls: Arg
	get() = argOrThrow("extract", "cls")
var ArgValues.extractCls: Long?
	get() = get(args.extractCls) as Long?
	set(value) { set(args.extractCls, value) }
val ArgValues.extractClsOrThrow: Long
	get() = getOrThrow(args.extractCls) as Long

val Args.extractBox: Arg
	get() = argOrThrow("extract", "box")
val Args.extractBoxDefault: Long
	get() = (extractBox.default as ArgValue.VInt).value
var ArgValues.extractBox: Long?
	get() = get(args.extractBox) as Long?
	set(value) { set(args.extractBox, value) }

val Args.ctfMinRes: Arg
	get() = argOrThrow("ctf", "min_res")
val ArgValues.ctfMinResOrThrow: Double
	get() = getOrThrow(args.ctfMinRes) as Double

val Args.scopePixel: Arg
	get() = argOrThrow("scope", "pixel")
val ArgValues.scopePixel: ValueA?
	get() = (get(args.scopePixel) as Double?)?.let { ValueA(it) }
val ArgValues.scopePixelOrThrow: ValueA
	get() = ValueA(getOrThrow(args.scopePixel) as Double)

val Args.scopeVoltage: Arg
	get() = argOrThrow("scope", "voltage")
val ArgValues.scopeVoltage: Double?
	get() = get(args.scopeVoltage) as Double?

val Args.scopeDoseRate: Arg
	get() = argOrThrow("scope", "dose_rate")
val ArgValues.scopeDoseRate: Double?
	get() = get(args.scopeDoseRate) as Double?
val ArgValues.scopeDoseRateOrDefault: Double
	get() = getOrDefault(args.scopeDoseRate) as Double

val Args.movieBin: Arg
	get() = argOrThrow("movie", "bin")
val ArgValues.movieBin: Int?
	get() = get(args.movieBin) as Int?
val ArgValues.movieBinOrDefault: Int
	get() = getOrDefault(args.movieBin) as Int

val Args.tomoRecBinning: Arg
	get() = argOrThrow("tomo_rec", "binning")
val ArgValues.tomoRecBinning: Int?
	get() = get(args.tomoRecBinning) as Int?
val ArgValues.tomoRecBinningOrDefault: Int
	get() = getOrDefault(args.tomoRecBinning) as Int

val Args.refineMode: Arg
	get() = argOrThrow("refine", "mode")
var ArgValues.refineMode: String?
	get() = get(args.refineMode) as String?
	set(value) { set(args.refineMode, value) }

val Args.reconstructCutoff: Arg
	get() = argOrThrow("reconstruct", "cutoff")
var ArgValues.reconstructCutoff: String?
	get() = get(args.reconstructCutoff) as String?
	set(value) { set(args.reconstructCutoff, value) }

val Args.streamTransferTarget: Arg
	get() = argOrThrow("stream", "transfer_target")
var ArgValues.streamTransferTarget: String?
	get() = get(args.streamTransferTarget) as String?
	set(value) { set(args.streamTransferTarget, value) }

val Args.streamSessionGroup: Arg
	get() = argOrThrow("stream", "session_group")
var ArgValues.streamSessionGroup: String?
	get() = get(args.streamSessionGroup) as String?
	set(value) { set(args.streamSessionGroup, value) }

val Args.streamSessionName: Arg
	get() = argOrThrow("stream", "session_name")
var ArgValues.streamSessionName: String?
	get() = get(args.streamSessionName) as String?
	set(value) { set(args.streamSessionName, value) }

val Args.streamTransferLocal: Arg
	get() = argOrThrow("stream", "transfer_local")
var ArgValues.streamTransferLocal: Boolean?
	get() = get(args.streamTransferLocal) as Boolean?
	set(value) { set(args.streamTransferLocal, value) }

val Args.importMode: Arg
	get() = argOrThrow("import", "mode")
var ArgValues.importMode: String?
	get() = get(args.importMode) as String?
	set(value) { set(args.importMode, value) }

val Args.importReadStar: Arg
	get() = argOrThrow("import", "read_star")
var ArgValues.importReadStar: Boolean?
	get() = get(args.importReadStar) as Boolean?
	set(value) { set(args.importReadStar, value) }

val Args.importRelionPath: Arg
	get() = argOrThrow("import", "relion_path")
val ArgValues.importRelionPath: String?
	get() = get(args.importRelionPath) as String?
val ArgValues.importRelionPathOrThrow: String
	get() = getOrThrow(args.importRelionPath) as String

val Args.sharpenCistemHighResBfactor: Arg
	get() = argOrThrow("sharpen_cistem", "high_res_bfactor")
val Args.defaultSharpenCistemHighResBfactor: Double
	get() = sharpenCistemHighResBfactor.defaultOrThrow.value as Double
val ArgValues.sharpenCistemHighResBfactor: Double?
	get() = get(args.sharpenCistemHighResBfactor) as Double?


val Args.detectMethodExists: Boolean
	get() = arg("detect", "method") != null
val Args.detectMethod: Arg
	get() = argOrThrow("detect", "method")
val ArgValues.detectMethod: DetectMethod?
	get() = DetectMethod[get(args.detectMethod) as String?]
val ArgValues.detectMethodOrDefault: DetectMethod
	get() = DetectMethod[getOrDefault(args.detectMethod) as String]
		?: throw NoSuchElementException(
			"detectMethod default ${getOrDefault(args.detectMethod)} is invalid."
				+ " Need one of ${DetectMethod.values().map { it.id }}"
		)

@Serializable
enum class DetectMethod(val id: String, val particlesList: (ownerId: String) -> ParticlesList?) {

	None("none", { null }),
	Auto("auto", { ParticlesList.autoParticles2D(it) }),
	All("all", { ParticlesList.autoParticles2D(it) }),
	Manual("manual", { ParticlesList.manualParticles2D(it) }),
	Import("import", { ParticlesList.autoParticles2D(it) }),
	PYPTrain("pyp-train", { ParticlesList.manualParticles2D(it) }),
	PYPEval("pyp-eval", { ParticlesList.autoParticles2D(it) }),
	TopazTrain("topaz-train", { ParticlesList.manualParticles2D(it) }),
	TopazEval("topaz-eval", { ParticlesList.autoParticles2D(it) });

	companion object {
		operator fun get(id: String?): DetectMethod? =
			values().find { it.id == id }
	}
}

val Args.detectRad: Arg
	get() = argOrThrow("detect", "rad")
val ArgValues.detectRad: ValueA?
	get() = (get(args.detectRad) as Double?)?.let { ValueA(it) }
val ArgValues.detectRadOrThrow: ValueA
	get() = ValueA(getOrThrow(args.detectRad) as Double)


val Args.tomoVirMethodExists: Boolean
	get() = arg("tomo_vir", "method") != null
val Args.tomoVirMethod: Arg
	get() = argOrThrow("tomo_vir", "method")
val ArgValues.tomoVirMethod: TomoVirMethod?
	get() = TomoVirMethod[get(args.tomoVirMethod) as String?]
val ArgValues.tomoVirMethodOrDefault: TomoVirMethod
	get() = TomoVirMethod[getOrDefault(args.tomoVirMethod) as String]
		?: throw NoSuchElementException(
			"tomoVirMethod default ${getOrDefault(args.tomoVirMethod)} is invalid."
			+ " Need one of ${TomoVirMethod.values().map { it.id }}"
		)

@Serializable
enum class TomoVirMethod(val id: String, val particlesList: (ownerId: String) -> ParticlesList?) {

	None("none", { null }),
	Auto("auto", { ParticlesList.autoVirions(it) }),
	Manual("manual", { ParticlesList.manualVirions(it) }),
	PYPTrain("pyp-train", { ParticlesList.manualVirions(it) }),
	PYPEval("pyp-eval", { ParticlesList.autoVirions(it) }),
	TopazTrain("topaz-train", { ParticlesList.manualVirions(it) }),
	TopazEval("topaz-eval", { ParticlesList.autoVirions(it) });

	companion object {
		operator fun get(id: String?): TomoVirMethod? =
			values().find { it.id == id }
	}
}

val Args.tomoVirRad: Arg
	get() = argOrThrow("tomo_vir", "rad")
val ArgValues.tomoVirRad: ValueA?
	get() = (get(args.tomoVirRad) as Double?)?.let { ValueA(it) }
val ArgValues.tomoVirRadOrDefault: ValueA
	get() = ValueA(getOrDefault(args.tomoVirRad) as Double)


val Args.tomoVirBinn: Arg
	get() = argOrThrow("tomo_vir", "binn")
val ArgValues.tomoVirBinn: Long?
	get() = get(args.tomoVirBinn) as Long?
val ArgValues.tomoVirBinnOrDefault: Long
	get() = getOrDefault(args.tomoVirBinn) as Long


val Args.tomoSpkMethodExists: Boolean
	get() = arg("tomo_spk", "method") != null
val Args.tomoSpkMethod: Arg
	get() = argOrThrow("tomo_spk", "method")
val ArgValues.tomoSpkMethod: TomoSpkMethod?
	get() = TomoSpkMethod[get(args.tomoSpkMethod) as String?]
val ArgValues.tomoSpkMethodOrDefault: TomoSpkMethod
	get() = TomoSpkMethod[getOrDefault(args.tomoSpkMethod) as String]
		?: throw NoSuchElementException(
			"tomoSpkMethod default ${getOrDefault(args.tomoSpkMethod)} is invalid."
				+ " Need one of ${TomoSpkMethod.values().map { it.id }}"
		)


@Serializable
enum class TomoSpkMethod(val id: String, val particlesList: (ownerId: String) -> ParticlesList?) {

	None("none", { null }),
	Auto("auto", { ParticlesList.autoParticles3D(it) }),
	Import("import", { ParticlesList.autoParticles3D(it) }),
	Manual("manual", { ParticlesList.manualParticles3D(it) }),
	MiloTrain("milo-train", { ParticlesList.manualParticles3D(it) }),
	MiloEval("milo-eval", { ParticlesList.autoParticles3D(it) }),
	PYPTrain("pyp-train", { ParticlesList.manualParticles3D(it) }),
	PYPEval("pyp-eval", { ParticlesList.autoParticles3D(it) });

	companion object {
		operator fun get(id: String?): TomoSpkMethod? =
			values().find { it.id == id }
	}
}


val Args.tomoSpkRad: Arg
	get() = argOrThrow("tomo_spk", "rad")
val ArgValues.tomoSpkRad: ValueA?
	get() = (get(args.tomoSpkRad) as Double?)?.let { ValueA(it) }
val ArgValues.tomoSpkRadOrDefault: ValueA
	get() = ValueA(getOrDefault(args.tomoSpkRad) as Double)



val Args.tomoSrfMethodExists: Boolean
	get() = arg("tomo_srf", "detect_method") != null
val Args.tomoSrfMethod: Arg
	get() = argOrThrow("tomo_srf", "detect_method")
val ArgValues.tomoSrfMethod: TomoSrfMethod?
	get() = TomoSrfMethod[get(args.tomoSrfMethod) as String?]
val ArgValues.tomoSrfMethodOrDefault: TomoSrfMethod
	get() = TomoSrfMethod[getOrDefault(args.tomoSrfMethod) as String]
		?: throw NoSuchElementException(
			"tomoSrfMethod default ${getOrDefault(args.tomoSrfMethod)} is invalid."
				+ " Need one of ${TomoSrfMethod.values().map { it.id }}"
		)

@Serializable
enum class TomoSrfMethod(val id: String, val particlesList: (ownerId: String) -> ParticlesList?) {

	None("none", { null }),
	Template("template", ParticlesList::autoParticles3D),
	Mesh("mesh", ParticlesList::autoParticles3D);

	companion object {
		operator fun get(id: String?): TomoSrfMethod? =
			values().find { it.id == id }
	}
}



/**
 * arguments for micromon itself and not pyp
 */
object MicromonArgs {

	val slurmLaunchCpus = Arg(
		groupId = "slurm",
		argId = "launch_tasks",
		name = "Threads (launch task)",
		description = "Number of CPUs used during launching",
		type = ArgType.TInt(),
		required = false,
		default = ArgValue.VInt(1),
		target = ArgTarget.Micromon
	)

	val slurmLaunchMemory = Arg(
		groupId = "slurm",
		argId = "launch_memory",
		name = "Memory (launch task)",
		description = "Amount of memory used during launching (0=all memory in node, GB)",
		type = ArgType.TInt(),
		required = false,
		default = ArgValue.VInt(4),
		target = ArgTarget.Micromon
	)

	val slurmLaunchWalltime = Arg(
		groupId = "slurm",
		argId = "launch_walltime",
		name = "Walltime (launch task)",
		description = "Max running time for each task (dd-hh:mm:ss)",
		type = ArgType.TStr(),
		required = false,
		default = ArgValue.VStr("2:00:00"),
		target = ArgTarget.Micromon
	)

	val slurmLaunchAccount = Arg(
		groupId = "slurm",
		argId = "launch_account",
		name = "Slurm account (launch task)",
		description = "Charge resources used by this job to specified account",
		type = ArgType.TStr(),
		required = false,
		advanced = true,
		default = null,
		target = ArgTarget.Micromon
	)

	val slurmLaunchQueue = Arg(
		groupId = "slurm",
		argId = "launch_queue",
		name = "CPU partition (launch task)",
		description = "SLURM partition to submit CPU jobs",
		type = ArgType.TStr(),
		input = ArgInput.ClusterQueue(ArgInput.ClusterQueue.Group.Cpu),
		required = false,
		target = ArgTarget.Micromon
	)

	val slurmLaunchGres = Arg(
		groupId = "slurm",
		argId = "launch_gres",
		name = "Gres (launch task)",
		description = "Comma separated list of generic resource scheduling options",
		type = ArgType.TStr(),
		default = ArgValue.VStr(""),
		required = false,
		advanced = true,
		target = ArgTarget.Micromon
	)

	val slurmLaunch = listOf(
		slurmLaunchCpus,
		slurmLaunchMemory,
		slurmLaunchGres,
		slurmLaunchAccount,
		slurmLaunchWalltime,
		slurmLaunchQueue
	)

	val args = Args(
		blocks = emptyList(),
		groups = emptyList(),
		args = slurmLaunch
	)
}

val ArgValues.slurmLaunchCpus: Long
	get() = getOrDefault(MicromonArgs.slurmLaunchCpus) as Long

val ArgValues.slurmLaunchMemory: Long
	get() = getOrDefault(MicromonArgs.slurmLaunchMemory) as Long

val ArgValues.slurmLaunchWalltime: String
	get() = getOrDefault(MicromonArgs.slurmLaunchWalltime) as String

val ArgValues.slurmLaunchAccount: String?
	get() = get(MicromonArgs.slurmLaunchAccount) as String?

val ArgValues.slurmLaunchQueue: String?
	get() = get(MicromonArgs.slurmLaunchQueue) as String?

val ArgValues.slurmLaunchGres: String?
	get() = get(MicromonArgs.slurmLaunchGres) as String?


/**
 * Args intended for pyp (or mock pyp), but aren't defined in the argument config file
 */
object MicromonArgsForPyp {

	private const val GROUP_ID = "micromon"

	val block = Arg(
		groupId = GROUP_ID,
		argId = "block",
		name = "",
		description = "",
		type = ArgType.TStr()
	)

	val all = listOf(
		block
	)
}

var ArgValues.micromonBlock: String?
	get() = get(MicromonArgsForPyp.block) as String?
	set(value) { set(MicromonArgsForPyp.block, value) }
