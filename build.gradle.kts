import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.PluginConfigurationImpl
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URL
import java.util.Properties
import org.tomlj.Toml


plugins {
	val kotlinVersion: String by System.getProperties()
	kotlin("plugin.serialization") version kotlinVersion
	kotlin("multiplatform") version kotlinVersion
	id("org.jetbrains.dokka") version kotlinVersion
	val kvisionVersion: String by System.getProperties()
	id("kvision") version kvisionVersion
}

buildscript {
	repositories {
		mavenCentral()
	}
	dependencies {
		classpath("org.tomlj:tomlj:1.0.0")
	}
}

group = "edu.duke.bartesaghi"


/**
 * This version number describes the nextPYP API.
 * It's not the same as the nextPYP version!
 *
 * This version number has defined formal semantics that helps nextPYP API clients
 * decide if they're compatible with the API or not.
 * The formal semantics are called Semantic Versioning: https://semver.org/
 *
 * Briefly, the version number should always have three integer components: major.minor.patch.
 * If you make a change to the API that would break any existing clients, you must increment the major number.
 * Otherwise, if you add new functionality without breaking existing funtionality, you must increment the minor number.
 * Otherwise, if you fix a bug or patch a vulnerability without breaking compatibility, you must increment the patch number.
 *
 * Also, incrementing 9 yields 10. eg, incrementing the minor version of 1.9.0 yields 1.10.0
 */
val apiVersion = "1.0.0"


repositories {
	mavenCentral()
	@Suppress("DEPRECATION") // yes, we know it's deprecated, but we don't get to choose where dependencies come from
	jcenter() // needed for some JetBrains dependencies... still works somehow
}

// Versions
val javaVersion: String by project
val kotlinVersion: String by System.getProperties()
val kvisionVersion: String by System.getProperties()
val kotlinLanguageVersion: String by project
val serializationVersion: String by project
val coroutinesVersion: String by project
val ktorVersion: String by project
val exposedVersion: String by project
val hikariVersion: String by project
val h2Version: String by project
val pgsqlVersion: String by project
val kweryVersion: String by project
val logbackVersion: String by project
val commonsCodecVersion: String by project
val jdbcNamedParametersVersion: String by project

// create a default local.properties if needed
val localPropsPath = projectDir.resolve("local.properties")
if (!localPropsPath.exists()) {
	localPropsPath.writeText("""
		|
		|# the path to the pyp sources
		|pypDir=../pyp
		|
	""".trimMargin())
}

// read info about the local environment from local.properties, not gradle.properties
val localProps = Properties().apply {
	localPropsPath.bufferedReader().use { reader ->
		load(reader)
	}
}
val pypDir = projectDir.resolve(localProps["pypDir"] as String)
val rawDataDir = projectDir.resolve(localProps["rawDataDir"] as? String ?: "../data")
val clientDir = (localProps["clientDir"] as? String)?.let { projectDir.resolve(it) }

val webDir = file("src/frontendMain/web")
val runDir = projectDir.resolve("run")

// read the version number from pyp, if possible
pypDir.resolve("nextpyp.toml")
	.takeIf { it.exists() }
	?.let { nextpypPath ->

		// read the TOML file
		val doc = Toml.parse(nextpypPath.readText())
		if (doc.hasErrors()) {
			throw Error("Failed to parse ${nextpypPath.absolutePath}:\n${doc.errors().joinToString("\n")}")
		}

		// read the version, if any
		doc.getString("version")
			?.let { version = it }
	}

println("NextPYP version: $version")

// figure out if we're building a development build or not, so we can optimize the task dependencies
val isDevBuildTask = gradle.startParameter.taskNames.any { it in listOf(
	"vmContainerRun",
	"vmContainerRerun",
	"containerRun",
	"containerRerun"
) }


kotlin {

	val sharedCompilerArgs = emptyList<String>()

	jvm("backend") {
		compilations.all {
			kotlinOptions {
				jvmTarget = javaVersion
				apiVersion = kotlinLanguageVersion
				languageVersion = kotlinLanguageVersion
				// show compiler errors when we misuse nullable values from Java
				// see: https://kotlinlang.org/docs/java-interop.html#jsr-305-support
 				// and: https://github.com/Kotlin/KEEP/blob/master/proposals/jsr-305-custom-nullability-qualifiers.md#compiler-configuration-for-jsr-305-support
				freeCompilerArgs += listOf("-Xjsr305=strict")
				freeCompilerArgs += sharedCompilerArgs
			}
		}
	}
	// The new Kotlin/JS IR compiler v1.8.22 can't deal with the older versions of the KVision libraries.
	// The code itself compiles just fine now, but the CSS resources get omitted from the compiled version somehow.
	// The new compiler docs say the reason might be because older libraries are incompatible with the new compiler.
	// see: https://kotlinlang.org/docs/js-ir-compiler.html#current-limitations-of-the-ir-compiler
	// We're a couple years behind the current KVision release, but upgrading KVision is such a pain in the ass
	// it's REALLY not worth the trouble unless we have absolutely no other choice.
	// So we'll continue to use the old legacy (non-IR) compiler for as long as we're using the old version of KVision
	// and as long as JetBrains will continue supporting it.
	// Tragically, the newest version of the legacy compiler v1.8.22 doesn't work on our code either. =(
	// And neither does the v1.7.21 compiler. >8[
	// Looks like the newest compiler version we can use is v1.6.x. *sigh*
	js("frontend", compiler=LEGACY) {
		compilations.all {
			kotlinOptions {
				apiVersion = kotlinLanguageVersion
				languageVersion = kotlinLanguageVersion
				freeCompilerArgs += sharedCompilerArgs
			}
		}
		browser {
			runTask {
				outputFileName = "main.bundle.js"
				sourceMaps = true
				// NOTE: this dev server isn't used at all in container-land
				devServer = KotlinWebpackConfig.DevServer(
					open = false,
					port = 3000,
					proxy = mutableMapOf(
						"/kv/*" to "http://localhost:8080",
						"/kvws/*" to mapOf("target" to "ws://localhost:8080", "ws" to true)
					),
					contentBase = mutableListOf("${layout.buildDirectory.toPath()}/processedResources/frontend/main")
				)
			}
			webpackTask {
				outputFileName = "main.bundle.js"
				// see: https://webpack.js.org/configuration/devtool/
				devtool = "eval-source-map"
				// TODO: how to differentiate devtool between dev and prod?
			}
			testTask {
				useKarma {
					//useChromeHeadless()
					useFirefoxHeadless()
				}
			}
		}
		binaries.executable()
	}
	sourceSets {
		val commonMain by getting {
			dependencies {
				api("io.kvision:kvision-server-ktor:$kvisionVersion")

				// force the full release version of coroutines to avoid IDE errors (not the release candidate that KVision depends on)
				implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

				// override the kotlinx.serialization version provided by the gradle plugin to newer version to get a bugfix, see:
				// https://github.com/Kotlin/kotlinx.serialization/issues/1488
				implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
			}
			kotlin.srcDir("build/generated-src/common")
		}
		val commonTest by getting {
			dependencies {
				implementation(kotlin("test-common"))
				implementation(kotlin("test-annotations-common"))
			}
		}
		val backendMain by getting {
			dependencies {
				implementation(kotlin("stdlib-jdk8"))
				implementation(kotlin("reflect"))
				implementation("io.ktor:ktor-server-netty:$ktorVersion") // Apache 2
				implementation("io.ktor:ktor-auth:$ktorVersion") // Apache 2
				implementation("io.ktor:ktor-client-cio:$ktorVersion") // Apache 2
				implementation("ch.qos.logback:logback-classic:$logbackVersion") // LGPL

				// TODO: we don't even use these things, they were included in KVision, should we get rid of them?
				implementation("com.h2database:h2:$h2Version")
				implementation("org.jetbrains.exposed:exposed:$exposedVersion")
				implementation("org.postgresql:postgresql:$pgsqlVersion")
				implementation("com.zaxxer:HikariCP:$hikariVersion")
				implementation("commons-codec:commons-codec:$commonsCodecVersion")
				implementation("com.axiomalaska:jdbc-named-parameters:$jdbcNamedParametersVersion")
				implementation("com.github.andrewoma.kwery:core:$kweryVersion")

				implementation("com.github.jai-imageio:jai-imageio-core:1.3.1") // BSD 3-clause
				implementation("org.mongodb:mongodb-driver-sync:4.4.0") // Apache 2
				implementation("org.tomlj:tomlj:1.0.0") // Apache 2
				implementation("de.mkammerer:argon2-jvm:2.7") // LGPL
				implementation("io.seruco.encoding:base62:0.1.3") // MIT
				implementation("com.github.mwiede:jsch:0.1.66") // BSD
				implementation("com.fasterxml.jackson.core:jackson-databind:2.12.3") // Apache 2
				implementation("org.apache.commons:commons-vfs2:2.9.0") // Apache 2
				implementation("org.reflections:reflections:0.10.2") // Apache 2

				// library for WebP support in ImageIO
				implementation("io.github.darkxanter:webp-imageio:0.2.3") // Apache 2
				/* NOTE:
					The latest version of this library is 0.3.2, but we can't use it because of dependency hell.
					The 0.3.2 jar bundles a native libary that depends on libc 2.29.
					To get libc 2.29+, we'd have to base the container on Rocky 9+.
					BUT!
					Mongo DB 4.4 isn't supported in Rocky 9. The mongodb-org v4.4 package is not present in the repo for RHEL/etc 9!
					Mongo DB 5+ is present in the repo, and we could use that in theory,
					but upgrading to a new DB version apparently requires a database migration, which we'd like to avoid.
					So we'll use 0.2.3 of the WebP jar which depends on a libc that is present in Rocky 8.
				*/

				// CUDA libraries
				// see: https://github.com/jcuda/jcuda-main/blob/master/USAGE.md
				val jcudaVersion = "12.0.0"
				implementation("org.jcuda:jcuda:$jcudaVersion") {
					isTransitive = false
				}
				runtimeOnly("org.jcuda:jcuda-natives:$jcudaVersion:linux-x86_64")

				// NOTE: if you change dependency libraries, run the `image` gradle task to update the `build/libs` folder
			}
		}
		val backendTest by getting {
			dependencies {
				// NOTE: this is the newest version of kotest we can use with Kotlin 1.6
				val kotestVersion = "5.5.5"
				implementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
				implementation("io.kotest:kotest-assertions-core-jvm:$kotestVersion")
			}
		}
		val frontendMain by getting {
			resources.srcDir(webDir)
			dependencies {
				// NOTE: KVision itself is MIT licensed
				implementation("io.kvision:kvision:$kvisionVersion")
				implementation("io.kvision:kvision-bootstrap:$kvisionVersion")
				implementation("io.kvision:kvision-bootstrap-css:$kvisionVersion")
				implementation("io.kvision:kvision-bootstrap-select:$kvisionVersion")
				implementation("io.kvision:kvision-bootstrap-select-remote:$kvisionVersion")
				implementation("io.kvision:kvision-bootstrap-datetime:$kvisionVersion")
				implementation("io.kvision:kvision-bootstrap-spinner:$kvisionVersion")
				implementation("io.kvision:kvision-bootstrap-upload:$kvisionVersion")
				implementation("io.kvision:kvision-bootstrap-dialog:$kvisionVersion")
				implementation("io.kvision:kvision-fontawesome:$kvisionVersion")
				implementation("io.kvision:kvision-i18n:$kvisionVersion")
				implementation("io.kvision:kvision-richtext:$kvisionVersion")
				implementation("io.kvision:kvision-handlebars:$kvisionVersion")
				implementation("io.kvision:kvision-datacontainer:$kvisionVersion")
				implementation("io.kvision:kvision-redux:$kvisionVersion")
				implementation("io.kvision:kvision-chart:$kvisionVersion")
				implementation("io.kvision:kvision-tabulator:$kvisionVersion")
				implementation("io.kvision:kvision-pace:$kvisionVersion")
				implementation("io.kvision:kvision-moment:$kvisionVersion")
				//implementation("io.kvision:kvision-routing-navigo-ng:$kvisionVersion")
				implementation("io.kvision:navigo-kotlin-ng:0.0.3")
				// NOTE: The above library uses a module from a newer KVision release
				//       to try to work around a bug in Navigo v8.8.12 that was fixed in a later release.
				//       It uses the Navigo wrapper rather than the KVision module around it,
				//       because we re-wrote the KVision shim code in our project anyway.
				implementation("io.kvision:kvision-toast:$kvisionVersion")

				implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.7.2") // Apache 2

				/* Always explicitly pick versions for all JS dependencies!!

					The Kotlin front-end plugin will warn us if we try to add a dependency without a version.
					Don't ignore those warnings, you'll regret it later if you do.

					Tragically, the default behavior for npm is to automatically download newest
					versions of libraries even when rebuilding an existing project!!
					Naturally, this creates unexpected bugs when OF COURSE your code isn't
					automatically compatible with newer versions of your dependencies.

					Even worse, the version you didn't know you were depending on gets overwritten by the rebuild
					so you can't go look it up after you suddenly realize you need it.
					The only way to restore working order to your project is to guess what version you depended on
					for each end every dependency that's now broken by an unexpected (and unwanted) update.

					Thankfully, the newest Kotlin/JS gradle plugin uses yarn,
					which will ossify dependency versions into a yarn.lock file.
					Make sure to include the kotlin-js-store/yarn.lock file in the git repo.
				*/

				// add JavaScript dependencies here, then require() or @JsModule() them in kotlin source somewhere
				implementation(npm("three", "0.138.3")) // MIT
				implementation(npm("dat.gui", "0.7.9")) // Apache-2.0
				implementation(npm("plotly.js", "1.58.1")) // MIT
				implementation(npm("nouislider", "14.6.3")) // MIT
				implementation(npm("photoswipe", "4.1.3")) // MIT
				implementation(npm("hyperlist", "1.0.0")) // MIT
				implementation(npm("js-cookie", "2.2.1")) // MIT
				implementation(npm("webcola", "3.4.0")) // MIT
				implementation(npm("@projectstorm/react-diagrams", "6.2.0")) // MIT
				implementation(npm("@ltd/j-toml", "1.12.2")) // LGPL 3
				implementation(npm("ansicolor", "1.1.100")) // Unlicense

				// dependencies for react-diagrams, see: https://projectstorm.gitbook.io/react-diagrams/getting-started
				implementation(npm("closest", "0.0.1")) // MIT
				implementation(npm("lodash", "4.17.20")) // MIT
				implementation(npm("react", "16.14.0")) // MIT
				implementation(npm("react-dom", "16.14.0")) // MIT
				implementation(npm("ml-matrix", "6.5.3")) // MIT
				implementation(npm("dagre", "0.8.5")) // MIT
				implementation(npm("pathfinding", "0.4.18")) // MIT
				implementation(npm("paths-js", "0.4.11")) // Apache 2
				implementation(npm("@emotion/core", "10.1.1")) // MIT
				implementation(npm("@emotion/styled", "10.0.27")) // MIT
				implementation(npm("resize-observer-polyfill", "1.5.1")) // MIT
			}
			kotlin.srcDir("build/generated-src/frontend")
		}
		val frontendTest by getting {
			dependencies {
				implementation(kotlin("test-js"))
				implementation("io.kvision:kvision-testutils:$kvisionVersion")
			}
		}
	}
}


// Tragically, the react-diagrams maintainer didn't get SemVer correct.
// I dont blame them, getting SemVer correct is a form of predicting the future.
// And predicting the future is hard. =/
// To fix it, we need to override version numbers for transitive dependencies, see:
// https://classic.yarnpkg.com/en/docs/selective-version-resolutions/
// https://blog.jetbrains.com/kotlin/2020/11/kotlin-1-4-20-released/
rootProject.plugins.withType<YarnPlugin> {
	rootProject.the<YarnRootExtension>().apply {
		resolution("@projectstorm/react-canvas-core", "6.2.0")
		resolution("@projectstorm/react-diagrams-core", "6.2.0")
		resolution("@projectstorm/react-diagrams-defaults", "6.2.0")
		resolution("@projectstorm/react-diagrams-routing","6.2.0")
	}
}


// enable test runner for test tasks
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}


fun getNodeJsBinaryExecutable(): String {
	val nodeDir = NodeJsRootPlugin.apply(rootProject).nodeJsSetupTaskProvider.get().destination
	val isWindows = System.getProperty("os.name").lowercase().contains("windows")
	val nodeBinDir = if (isWindows) nodeDir else nodeDir.resolve("bin")
	val command = NodeJsRootPlugin.apply(rootProject).nodeCommand
	val finalCommand = if (isWindows && command == "node") "node.exe" else command
	return nodeBinDir.resolve(finalCommand).absolutePath
}

tasks {
	create("generatePotFile", Exec::class) {
		dependsOn("compileKotlinFrontend")
		executable = getNodeJsBinaryExecutable()
		args("${rootProject.layout.buildDirectory.toPath()}/js/node_modules/gettext-extract/bin/gettext-extract")
		inputs.files(kotlin.sourceSets["frontendMain"].kotlin.files)
		outputs.file("$projectDir/src/frontendMain/resources/i18n/messages.pot")
	}
}
afterEvaluate {
	tasks {
		getByName("frontendProcessResources", Copy::class) {
			dependsOn("compileKotlinFrontend")
			exclude("**/*.pot")
			doLast("Convert PO to JSON") {
				destinationDir.walkTopDown().filter {
					it.isFile && it.extension == "po"
				}.forEach {
					exec {
						executable = getNodeJsBinaryExecutable()
						args(
							"${rootProject.layout.buildDirectory.toPath()}/js/node_modules/gettext.js/bin/po2json",
							it.absolutePath,
							"${it.parent}/${it.nameWithoutExtension}.json"
						)
						println("Converted ${it.name} to ${it.nameWithoutExtension}.json")
					}
					it.delete()
				}
			}
		}
		create("frontendArchive", Jar::class).apply {

			// if we're building for development, put webpack in development mode
			val webpackTask = if (isDevBuildTask) {
				"frontendBrowserDevelopmentWebpack"
			} else {
				"frontendBrowserProductionWebpack"
			}

			dependsOn(webpackTask)
			group = "package"
			archiveAppendix.set("frontend")
			val distribution =
				project.tasks.getByName(webpackTask, KotlinWebpack::class).destinationDirectory
			from(distribution) {
				include("*.*")
			}
			from(webDir)
			duplicatesStrategy = DuplicatesStrategy.EXCLUDE
			into("/assets")
			inputs.files(distribution, webDir)
			outputs.file(archiveFile)
			manifest {
				attributes(
					mapOf(
						"Implementation-Title" to rootProject.name,
						"Implementation-Group" to rootProject.group,
						"Implementation-Version" to rootProject.version,
						"Timestamp" to System.currentTimeMillis()
					)
				)
			}
		}
		getByName("backendProcessResources", Copy::class) {
			duplicatesStrategy = DuplicatesStrategy.EXCLUDE
		}
		getByName("backendJar").group = "package"
		create("jar", Jar::class).apply {
			dependsOn("frontendArchive", "backendJar")
			group = "package"
			manifest {
				attributes(
					mapOf(
						"Implementation-Title" to rootProject.name,
						"Implementation-Group" to rootProject.group,
						"Implementation-Version" to rootProject.version,
						"Timestamp" to System.currentTimeMillis()
					)
				)
			}
			val dependencies = project.tasks["backendJar"].outputs.files +
				project.tasks["frontendArchive"].outputs.files
			dependencies.forEach {
				if (it.isDirectory) from(it) else from(zipTree(it))
			}
			inputs.files(dependencies)
			outputs.file(archiveFile)
			duplicatesStrategy = DuplicatesStrategy.EXCLUDE
		}
		create("backendRun", JavaExec::class) {
			dependsOn("compileKotlinBackend")
			group = "run"
			mainClass = "edu.duke.bartesaghi.micromon.MainKt"
			classpath =
				configurations["backendRuntimeClasspath"] + project.tasks["compileKotlinBackend"].outputs.files +
						project.tasks["backendProcessResources"].outputs.files
			workingDir = layout.buildDirectory.toFile()
		}
		getByName("compileKotlinBackend") {
			dependsOn("compileKotlinMetadata", "allMetadataJar", "generateBuildSources")
		}
		getByName("compileKotlinFrontend") {
			dependsOn("compileKotlinMetadata", "allMetadataJar", "generateBuildSources", "copyLocalModules")
		}

		create("copyLocalModules", Copy::class) {
			group = "nodejs"
			description = "copy our js modules from the src folder to the build folder where the compiler can find them"
			mustRunAfter("kotlinNpmInstall")
			val frontendDir = kotlin.sourceSets.getByName("frontendMain").kotlin.srcDirs.first().parentFile
			from(frontendDir.resolve("js"))
			into(layout.buildDirectory.dir("js/node_modules"))
		}
		getByName("frontendBrowserDevelopmentWebpack") {
			dependsOn("copyLocalModules")
			if (isDevBuildTask) {
				// force rebuilding webback in when in a dev task, otherwise the CSS doesn't get recompiled
				// TODO: maybe there's a better way to detect changes in CSS files so we don't have to redo this every build?
				outputs.upToDateWhen { false }
			}
		}
		getByName("frontendBrowserProductionWebpack") {
			dependsOn("copyLocalModules")
		}
		getByName("frontendBrowserTest") {
			dependsOn("copyLocalModules")
		}

		/** get all the backend java runtime dependencies (ie jars) */
		fun collectClasspath(): FileCollection =
			project.tasks["jar"].outputs.files
				.plus(configurations["backendRuntimeClasspath"])
				.filter { it.name.endsWith(".jar") }

		val classpathFileTask = create("classpathFile") {
			group = "build"
			description = "creates the classpath file needed by the website container"
			doLast {

				// resolve all the jar files against the libs folder
				layout.buildDirectory.toFile().resolve("classpath.txt")
					.writeText("""
						|-cp "\
						|${collectClasspath().joinToString(":\\\n") { "libs/${it.name}" }}
						|"
					""".trimMargin())
			}
		}

		create("image", Copy::class) {
			dependsOn("jar", classpathFileTask)
			group = "package"
			description = "makes the server runtime image"

			destinationDir = layout.buildDirectory.toFile().resolve("image")

			// copy all the dependency jars
			from(collectClasspath()) {
				into("libs")
			}
			
			// copy the classpath file
			from(layout.buildDirectory.file("classpath.txt")) {
				into("bin")
			}

			// copy the executable scripts
			from(projectDir.resolve("config")) {
				include("cli.sh")
				include("micromon.sh")
				into("bin")
				makeExecutable()
			}

			// NOTE: KTor is configured via the src/backendMain/resources/application.conf file,
			// rather than the command line, see:
			// https://ktor.io/servers/configuration.html#available-config
		}

		fun checkContainer(name: String) {
			val path = runDir.resolve(name)
			if (!path.exists()) {
				throw Error("""
					|Missing container $name, try running the task to build it, eg vmBuild___.
					|If you already have a pre-built container, move it to $path
				""".trimMargin())
			}
		}

		fun getConfigPath(): Path {
			// make sure the config.toml exists
			val configPath = runDir.toPath().resolve("config.toml")
			if (!configPath.exists()) {
				throw Error("create a config.toml in the run dir")
			}
			return configPath
		}

		create("containerRun") {
			group = "run"
			dependsOn("jar", classpathFileTask)
			mustRunAfter("containerStop")
			doLast {

				// make sure the needed files exist
				val configPath = getConfigPath()
				checkContainer("nextPYP.sif")

				// run the start script with the development jar
				exec {
					workingDir = runDir
					executable = "./start"
					environment("PYP_CONFIG", configPath.toString())
					environment("PYP_SRC", pypDir)
					args(projectDir.absolutePath, project.version)
				}
			}
		}

		create("containerStop") {
			group = "run"
			doLast {

				exec {
					workingDir = runDir
					executable = "./stop"

					// if the container isn't running, Singularity will return an error exit code,
					// which translates into a gradle exception by default
					// except, if the container isn't running, we've already won! =D
					// there's no need to stop it again, so just ignore errors from singularity entirely
					isIgnoreExitValue = true
				}
			}
		}

		create("containerRerun") {
			group = "run"
			dependsOn("containerStop", "containerRun")
		}

		// pick a unique id for our VM and its sub-objects
		val vmid = "streamPYP"
		val storageId = "$vmid-storage"
		val networkId = "vboxnet0"
		// NOTE: we can't actually choose the name of the host-only network, the name is automatically generated
		// using custom names isn't implemented for some reason, see: https://www.virtualbox.org/ticket/11919
		val micromonId = "micromon"
		val pypId = "pyp"
		val rawDataId = "rawdata"

		// for temporary files needed to build
		val buildDevDir = layout.buildDirectory.toPath().resolve("dev")

		// for persistent files that should be preserved between builds
		val devVmDir = projectDir.toPath().resolve("dev").resolve("vm")
		val drivePath = devVmDir.resolve("drive.vdi")

		val interfacePrefix = "192.168.56"
		val vmIp = "$interfacePrefix.5"

		// translate paths into the VM filesystem
		val vmMicromonDir = Paths.get("/media/$micromonId")
		val vmRunDir = vmMicromonDir.resolve("run")
		val vmPypDir = Paths.get("/media/$pypId")
		// TODO: need to share pyp folder

		create("vmCreate") {
			group = "dev"
			description = "Creates a VirtualBox virtual machine for development"
			doLast {

				// make a place to do VM things
				devVmDir.createFolderIfNeeded()

				// download the linux install ISO
				// CentOS is basically abandoned now (CentOS Stream is *not* the same thing!)
				// The next best thing to CentOS is now Rocky Linux, see for more info:
				// https://computingforgeeks.com/rocky-linux-vs-centos-stream-vs-rhel-vs-oracle-linux/

				val installIso = buildDevDir.createFolderIfNeeded().resolve("rocky.iso")
				if (!installIso.exists()) {
					URL("https://dl.rockylinux.org/vault/rocky/8.5/isos/x86_64/Rocky-8.5-x86_64-minimal.iso").let {
						println("dowloading $it ...")
						it.download(installIso)
					}
				}

				// remove any old VMs
				// but detatch the drive first so it doesn't get deleted too
				vbox("storageattach", ignoreResult=true) {
					add(vmid)
					add("--storagectl", storageId)
					add("--port", "1")
					add("--medium", "none")
				}
				vbox("closemedium", ignoreResult=true) {
					add("disk")
					add(drivePath.toString())
				}
				vbox("unregistervm", ignoreResult=true) {
					add(vmid)
					add("--delete")
					// NOTE: also deletes any attached drives
				}
				vbox("hostonlyif", ignoreResult=true) {
					add("remove", networkId)
				}
				vbox("dhcpserver", ignoreResult=true) {
					add("remove")
					add("--network", vboxInterfaceNetworkName(networkId))
				}

				// make the vbox image for rocky, see:
				// https://docs.rockylinux.org/guides/virtualization/vbox-rocky/
				// https://zaufi.github.io/administration/2012/08/31/vbox-setup-new-vm
				// https://docs.oracle.com/en/virtualization/virtualbox/6.1/user/vboxmanage.html#vboxmanage-intro

				vbox("createvm") {
					add("--name", vmid)
					add("--ostype", "RedHat_64")
					add("--register")
					add("--basefolder", devVmDir.toString())
				}
				vbox("modifyvm") {
					add(vmid)

					// pick resource limits
					// NOTE: the streaming daemons need at least 4 CPUs to run
					// NOTE: micrograph processing seems to need more than 4 GiB of RAM now
					//       looks like `unblur` from cistem is using most of it, 8 GiB seems to be enough for now
					add("--memory", (1024*8).toString())
					add("--cpus", "4")

					// add more than the default video memory, so we can run the rocky installer GUI
					// tragically, headless/automated installations are far too cumbersome to do here
					// see "kickstarting": https://docs.fedoraproject.org/en-US/Fedora/26/html/Installation_Guide/chap-kickstart-installations.html
					add("--vram", "256")
					// the vbox GUI seems to recommend using this option, but somehow it's not the default
					// turning it on solves a lot of performance issues for me though
					add("--graphicscontroller", "vmsvga")

					// try to use modern CPU instructions, for performance
					add("--hwvirtex", "on")
					//add("--hwvirtexexcl", "on") // apparently not supported by my version of vbox
					add("--vtxvpid", "on")

					// turn off defaults we don't need
					add("--accelerate3d", "off")
					add("--audio", "none")

					// configure the guest->host network path
					add("--nic1", "nat")

					// TODO
					//add("--natpf1", "ssh,tcp,,2222,,22")
					//add("--natpf2", "ssh,tcp,,8080,,8080")
				}

				// configure the host->guest network path
				// virtualbox should assign ip4 address 192.168.56.1 to the host adapter
				vbox("hostonlyif") {
					add("create")
				}
				vbox("hostonlyif") {
					add("ipconfig", networkId)
					add("--ip", "$interfacePrefix.1")
				}
				vbox("modifyvm") {
					add(vmid)
					add("--nic2", "hostonly")
					add("--hostonlyadapter2", networkId)
				}
				vbox("dhcpserver") {
					add("add")
					add("--network", vboxInterfaceNetworkName(networkId))
					add("--enable")

					add("--ip", "$interfacePrefix.2")
					add("--netmask", "255.255.255.0")

					// we won't actually use the dynamic IP range,
					// but vbox still requires us to set it
					add("--lowerip", "$interfacePrefix.10")
					add("--upperip", "$interfacePrefix.20")

					// give our VM a static lease on the IP
					add("--vm", vmid)
					add("--nic", "2")
					add("--fixed-address", vmIp)
				}

				// create the virtual drive for the VM
				val driveMiB = 30*1024 // 30 GiB, should be more than enough space
				if (!drivePath.exists()) {
					println("Creating drive for up to $driveMiB MiB ...")
					vbox("createmedium") {
						add("--filename", drivePath.toString())
						add("--format", "VDI")
						add("--size", driveMiB.toString())
						add("--variant", "Standard") // aka dynamically-sized, don't actually allocate space until we need it
					}
				}

				// attach the drive to the VM
				vbox("storagectl") {
					add(vmid)
					add("--name", storageId)
					add("--add", "sata")
					add("--controller", "IntelAHCI")
					add("--portcount", "4")
					add("--hostiocache", "off")
					add("--bootable", "on")
				}
				vbox("storageattach") {
					add(vmid)
					add("--storagectl", storageId)
					add("--port", "1")
					add("--medium", drivePath.toString())
					add("--type", "hdd")
				}

				// attach the install iso
				vbox("storageattach") {
					add(vmid)
					add("--storagectl", storageId)
					add("--port", "2")
					add("--medium", installIso.toString())
					add("--type", "dvddrive")
				}
				vbox("modifyvm") {
					add(vmid)
					add("--boot1", "dvd")
				}

				// boot the VM
				vbox("startvm") {
					add(vmid)
					add("--type", "gui") // need a human to run the Rocky installer
				}

				// notes for what to do in the installer GUI
				//  choose your language
				//  Installation Destination:
				//  	the defaults are fine, just click done
				//  Network & Host Name
				//      choose a host name, eg `nextpyp`
				//  Root Password
				//      leave this alone, don't pick a root password
				//  User Creation
				//  	might have to scroll down to see it
				//      make your user account, use the same name as the host user account!
				//      check the administrator option
				//      uncheck the password option, more annoying than useful for a dev vm
				//  Begin Installation!

				// when done, don't click reboot button
				// machine -> ACPI shutdown
			}
		}

		create("vmUpdate") {
			group = "dev"
			description = "Updates the operating system software in the VM, including kernel updates"
			doLast {

				// detatch the install iso
				vbox("storageattach", ignoreResult=true) {
					add(vmid)
					add("--storagectl", storageId)
					add("--port", "2")
					add("--medium", "none")
				}
				vbox("modifyvm") {
					add(vmid)
					add("--boot1", "disk")
				}

				vbox("startvm") {
					add(vmid)
					add("--type", "gui")
				}

				// log into the VM
				// $ sudo dnf update -y
				// $ shutdown now
			}
		}

		create("vmGuestAdditions") {
			group = "dev"
			description = "Sets up folder sharing in the virtual machine"
			doLast {

				// download the guest additions if needed
				// NOTE: v6.1.32 doesn't seem to work
				val guestIso = buildDevDir.createFolderIfNeeded().resolve("guestAdditions.iso")
				if (!guestIso.exists()) {
					URL("https://download.virtualbox.org/virtualbox/6.1.30/VBoxGuestAdditions_6.1.30.iso").download(guestIso)
				}
				if (!guestIso.exists()) {
					throw Error("can't find VirtualBox Guest Additions ISO at\n\t$guestIso")
				}

				// add guest additions media
				vbox("storageattach") {
					add(vmid)
					add("--storagectl", storageId)
					add("--port", "2")
					add("--medium", guestIso.toString())
					add("--type", "dvddrive")
				}

				vbox("startvm") {
					add(vmid)
					add("--type", "gui")
				}

				// $ sudo dnf install -y kernel-devel kernel-headers gcc make bzip2 perl elfutils-libelf-devel
				// $ sudo mount /dev/sr0 /mnt
				// $ sudo /mnt/VBoxLinuxAdditions.run
				// $ shutdown now

				// NOTE: rocky 9+ needs this too:
				// sudo dnf install epel-release -y
				// sudo dnf install -y dkms
			}
		}

		create("vmSetup") {
			group = "dev"
			description = "performs all the remaining setup to make the VM usable for development"
			doLast {

				// detatch the guest additions iso
				vbox("storageattach", ignoreResult=true) {
					add(vmid)
					add("--storagectl", storageId)
					add("--port", "2")
					add("--medium", "none")
				}

				// set up shared folders:

				// writeable access to micromon
				vbox("sharedfolder", ignoreResult=true) {
					add("remove")
					add(vmid)
					add("--name", micromonId)
				}
				vbox("sharedfolder") {
					add("add")
					add(vmid)
					add("--name", micromonId)
					add("--hostpath", projectDir.absolutePath)
					add("--automount")
					add("--auto-mount-point", "/media/$micromonId")
				}

				// read-only access to pyp, required
				if (!pypDir.exists()) {
					throw Error("pyp folder not found at \"$pypDir\". Make sure /local.properties `pypDir` has the correct path")
				}
				vbox("sharedfolder", ignoreResult=true) {
					add("remove")
					add(vmid)
					add("--name", pypId)
				}
				vbox("sharedfolder") {
					add("add")
					add(vmid)
					add("--name", pypId)
					add("--hostpath", pypDir.toString())
					add("--readonly")
					add("--automount")
					add("--auto-mount-point", "/media/$pypId")
				}

				// read-only access to raw data folder, if available
				vbox("sharedfolder", ignoreResult=true) {
					add("remove")
					add(vmid)
					add("--name", rawDataId)
				}
				if (rawDataDir.exists()) {
					vbox("sharedfolder") {
						add("add")
						add(vmid)
						add("--name", rawDataId)
						add("--hostpath", rawDataDir.toString())
						add("--readonly")
						add("--automount")
						add("--auto-mount-point", "/media/$rawDataId")
					}
				}

				vboxstart(vmid)
				try {

					// add the user to the `vboxsf` group so they can access shared folders
					vboxrun(vmid, "sudo usermod -G vboxsf -a `whoami`")

					// run the setup script
					vboxrun(vmid, "sudo /media/$micromonId/dev/setup.sh")

				} finally {
					vboxstop(vmid)
				}
			}
		}

		create("vmStart") {
			group = "dev"
			description = "starts the VM and leaves it running"
			doLast {

				vboxstart(vmid)

				println("""
					|
					|
					|You can ssh into your VM if you like:
					| $ ssh $vmIp
					| 
					|Be sure to run the `vmContainerRun` or `vmContainerRerun` tasks before accessing the website
				""".trimMargin())
			}
		}

		create("vmStop") {
			group = "dev"
			description = "stops the VM if it's running"
			doLast {

				vboxstop(vmid)
			}
		}

		create("vmGenerateConfig") {
			group = "run"
			description = "Generates the config.toml file (if it doesn't already exist) for the web server to understand the VM environment"
			doLast {

				val configPath = runDir.resolve("config.toml")
				if (!configPath.exists()) {

					copy {
						from(runDir.resolve("config.toml.template"))
						into(runDir)
						rename { configPath.name }
						expand("user" to System.getProperty("user.name"))
					}
				}
			}
		}


		fun buildContainer(name: String) {
			val cmd = listOf(
				"sudo", "singularity", "build",
				"--force", // allow overwriting the output files
				"$vmMicromonDir/run/$name.sif",
				"$vmMicromonDir/$name.def"
			)
			val cmdsh = cmd.joinToString(" ")
			vboxrun(vmid, "cd \"$vmMicromonDir\" && $cmdsh")
		}

		create("vmBuildNextPyp") {
			group = "build"
			description = "build the singularity image for the application server and MongoDB"
			dependsOn("image")
			doLast {
				buildContainer("nextPYP")
			}
		}

		create("vmBuildReverseProxy") {
			group = "build"
			description = "build the singularity image for the Caddy reverse proxy"
			doLast {
				buildContainer("reverse-proxy")
			}
		}

		create("vmBuildRustc") {
			group = "build"
			description = "build the singularity image for the Rust build environment"
			doLast {
				buildContainer("rustc")
			}
		}

		create("vmContainerRun") {
			group = "run"
			description = "Starts the micromon container inside the running VM"
			dependsOn("jar", "vmGenerateConfig", classpathFileTask)
			mustRunAfter("vmContainerStop")
			doLast {

				// make sure the needed files exist
				val configPath = getConfigPath()
				checkContainer("nextPYP.sif")

				// run the start script with the development jar
				val vmConfigPath = vmRunDir.resolve(configPath.fileName)
				vboxrun(vmid, "cd \"$vmRunDir\" && PYP_CONFIG=\"$vmConfigPath\" PYP_SRC=\"$vmPypDir\" ./start \"$vmMicromonDir\" ${project.version}")

				println("""
					|
					|
					|You can access the micromon website at:
					| http://$vmIp:8080/
					|
				""".trimMargin())
			}
		}

		create("vmContainerStop") {
			group = "run"
			description = "Stops the micromon container inside the running VM"
			doLast {

				// if the container isn't running, Singularity will return an error exit code,
				// which translates into a gradle exception by default
				// except, if the container isn't running, we've already won! =D
				// there's no need to stop it again, so just ignore errors from singularity entirely
				vboxrun(vmid, "cd \"$vmRunDir\" && ./stop", ignoreResult=true)
			}
		}

		create("vmContainerRerun") {
			group = "run"
			description = "Stops and then starts the micromon container inside the running VM"
			dependsOn("vmContainerStop", "vmContainerRun")
		}
		
		fun vmRustc(projectDir: Path): Path {

			checkContainer("rustc.sif")

			// config paths, relative to the `next` project dir
			val cargoRegistryDir = Paths.get("build/cargoRegistry")

			// create the cargo registry dir, if needed
			project.projectDir.toPath().resolve(cargoRegistryDir)
				.createFolderIfNeeded()

			// run cargo inside the rustc container inside the vm
			val vmDir = Paths.get("/media/micromon")
			val vmProjectDir = vmDir.resolve(projectDir)
			val vmCargoRegistryDir = vmDir.resolve(cargoRegistryDir)
			val containerCargoHome = "/usr/local/cargo"
			val binds = "--bind \"$vmCargoRegistryDir\":\"$containerCargoHome/registry\""
			val vmSifPath = vmDir.resolve("run/rustc.sif")
			vboxrun(vmid, "cd \"$vmProjectDir\" && apptainer exec $binds \"$vmSifPath\" cargo build --release")

			// return the out dir
			return projectDir.resolve("target/release")
		}

		create("vmBuildHostProcessor") {
			group = "build"
			description = "Build the host processor in the rustc container"
			doLast {

				val outDir = vmRustc(Paths.get("src/hostProcessor"))

				// copy the executable into the run folder
				copy {
					from(outDir.resolve("host-processor"))
					into(runDir)
				}
			}
		}

		create("vmBuildRunas") {
			group = "build"
			description = "Build the runas tool in the rustc container"
			doLast {

				val outDir = vmRustc(Paths.get("src/runas"))

				// copy the executable into the run folder
				copy {
					from(outDir.resolve("runas"))
					into(runDir)
				}
			}
		}

		create("generateBuildSources") {
			group = "build"
			description = "generates source files derived from build data"
			doLast {

				// make the version number available to common code
				val srcDir = layout.buildDirectory.toPath().resolve("generated-src/common")
				val packName = "${project.group}.micromon"
				val genPath = srcDir
					.resolve(packName.replace('.', '/'))
					.resolve("buildData.kt")

				genPath.parent.createFolderIfNeeded()

				Files.writeString(genPath, """
					|package $packName
					|
					|
					|object BuildData {
					|
					|	const val version = "${project.version}"
					|	const val apiVersion = "$apiVersion"
					|}
					|
				""".trimMargin())
			}
		}

		val dokkaPluginSubproject = project(":dokka-python-api")

		// https://kotlin.github.io/dokka/1.6.0/user_guide/gradle/usage/
		create("genPythonApi", DokkaTask::class) {
			group = "build"
			description = "generate sources for the Python API"
			dependsOn(":${dokkaPluginSubproject.name}:jar")

			// only look at the common source set
			dokkaSourceSets {
				configureEach {
					// by default, dokka wants to analyze the java code too
					// so just set the kotlin source folder
					sourceRoots.setFrom(kotlin.sourceSets.getByName("commonMain").kotlin.srcDirs.first().absolutePath)
				}
			}

			// set the output directory
			val dir = layout.buildDirectory.toPath()
				.resolve("generated-src")
				.resolve("python-api")
			outputDirectory.set(dir.toFile())

			// add our plugin
			dependencies {

				// declare the dokka plugin dependency
				// tragically, we can't just do the simple thing:
				// plugins(project(":dokka-python-api"))
				// because the plugin's dependencies include dokka-core
				// and dokka won't allow dokka-core on its own classpath =(

				// so we need to just hard-code the built jar path
				plugins(files("${dokkaPluginSubproject.layout.buildDirectory.toPath()}/libs/${dokkaPluginSubproject.name}.jar"))
			}

			// send arguments to our plugin, json-style
			pluginsConfiguration.add(PluginConfigurationImpl(
				fqPluginName = "edu.duke.bartesaghi.micromon.dokka.MicromonDokkaPlugin",
				serializationFormat = DokkaConfiguration.SerializationFormat.JSON,
				values = """
					|{
					|	"apiVersion": "$apiVersion"
					|}
				""".trimMargin()
			))

			doLast {
				// copy the generated sources to the client project, if available
				if (clientDir?.exists() == true) {

					val clientSrcDir = clientDir.resolve("src/nextpyp/client/")
					copy {
						from(dir.resolve("gen.py"))
						into(clientSrcDir)
					}

					// copy the pyp arguments config too
					val pypArgsPath = pypDir.resolve("config/pyp_config.toml")
						.takeIf { it.exists() }
						?: throw Error("pyp config file not found")
					copy {
						from(pypArgsPath)
						into(clientSrcDir)
					}
				}
			}
		}
	}
}

fun CopySpec.makeExecutable() {
	// make sure the script is executable
	fileMode = 0b111101101 // 0755 (Kotlin doesn't support octal literals)
}

fun symlink(src: File, dst: File) {
	exec {
		executable = "ln"
		args(
			"-s",
			src.absolutePath,
			if (dst.isDirectory) {
				"${dst.absolutePath}/"
			} else {
				dst.absolutePath
			}
		)

		// don't throw an error if the link already exists
		setIgnoreExitValue(true)
	}
}


interface Args {
	fun add(arg: String)
	fun add(vararg args: String)
}

/**
 * Run virtualbox commands
 */
fun vbox(command: String, ignoreResult: Boolean = false, block: Args.() -> Unit) {

	val commands = mutableListOf(
		"VBoxManage", command
	)

	// let the caller add args
	object : Args {
		override fun add(arg: String) {
			commands.add(arg)
		}
		override fun add(vararg args: String) {
			commands.addAll(args.toList())
		}
	}.block()

	// show the command
	println(
		commands
			.map { if (' ' in it) "\"$it\"" else it }
			.joinToString(" ")
	)

	// run the command
	exec {
		commandLine = commands

		if (ignoreResult) {
			isIgnoreExitValue = true
			val nullout = object : java.io.OutputStream() {
				override fun write(b: Int) {}
			}
			setStandardOutput(nullout)
			setErrorOutput(nullout)
		}
	}
}


/**
 * The --interface option to `VBoxManage dhcpserver` seems really unreliable!
 * The --network option seems to work much better, but we need to know
 * the magic spell to convert an interface name to a network name
 */
fun vboxInterfaceNetworkName(iface: String) =
	"HostInterfaceNetworking-$iface"


/**
 * starts the VM and waits for it to be ready
 */
fun vboxstart(vmid: String, timeoutSeconds: Int = 60) {

	vbox("startvm") {
		add(vmid)
		add("--type", "headless")
	}

	val startTime = System.currentTimeMillis()
	while (true) {

		val now = System.currentTimeMillis()
		val elapsedSeconds = (now - startTime)/1000
		println("waiting for VM to be ready ... $elapsedSeconds s of $timeoutSeconds s")
		if (elapsedSeconds > timeoutSeconds) {
			vboxstop(vmid)
			throw Error("Timed out waiting for VM to be ready")
		}

		// wait a bit, and then see if the VM is awake
		Thread.sleep(5000)

		val foo = exec {
			commandLine = listOf(
				"VBoxManage",
				"guestcontrol",
				vmid,
				"run",
				"--exe", "/usr/bin/uptime"
			)

			isIgnoreExitValue = true
			val nullout = object : java.io.OutputStream() {
				override fun write(b: Int) {}
			}
			setStandardOutput(nullout)
			setErrorOutput(nullout)
		}

		// was the command successful?
		if (foo.exitValue == 0) {
			println("VM started and ready!")
			break
		}
	}
}


/**
 * Sends the stop command to the VM
 */
fun vboxstop(vmid: String) {

	vbox("controlvm") {
		add(vmid)
		add("acpipowerbutton")
	}
}


/**
 * Runs a shell command in a running VM
 */
fun vboxrun(vmid: String, cmd: String, ignoreResult: Boolean = false) {
	vbox("guestcontrol", ignoreResult) {
		add(vmid)
		add("run")
		add("--exe", "/bin/sh")
		add("--")
		// looks like a later version of VirtualBox fixed this?
		//add("ignored") // first argument is ignored for some reason...
		add("-c", cmd)
	}
}


// some conveniences for files and paths

fun Path.exists(): Boolean =
	Files.exists(this)

fun Path.createFolderIfNeeded(): Path {
	Files.createDirectories(this)
	return this
}

fun URL.download(dst: Path) {
	dst.parent.createFolderIfNeeded()
	openStream().use {
		Files.copy(it, dst, StandardCopyOption.REPLACE_EXISTING)
	}
}


fun DirectoryProperty.toFile(): File =
	get().asFile

fun DirectoryProperty.toPath(): Path =
	toFile().toPath()
