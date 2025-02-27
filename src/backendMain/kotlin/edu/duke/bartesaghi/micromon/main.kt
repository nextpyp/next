package edu.duke.bartesaghi.micromon

import edu.duke.bartesaghi.micromon.auth.AppEndpoints
import edu.duke.bartesaghi.micromon.auth.auth
import edu.duke.bartesaghi.micromon.jobs.JobRunner
import edu.duke.bartesaghi.micromon.mongo.Database
import edu.duke.bartesaghi.micromon.pyp.PypService
import edu.duke.bartesaghi.micromon.pyp.Workflows
import edu.duke.bartesaghi.micromon.services.*
import edu.duke.bartesaghi.micromon.sessions.Session
import edu.duke.bartesaghi.micromon.sessions.SessionExport
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.sessions.Sessions
import io.ktor.sessions.cookie
import io.ktor.util.*
import io.ktor.util.pipeline.PipelineContext
import io.ktor.websocket.*
import io.kvision.remote.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream


/**
 * Process entry point for the website app
 */
fun main() {
	startWebServer(Config.instance.web, true)
}


fun startWebServer(config: Config.Web, wait: Boolean): NettyApplicationEngine {

	var initialized = false

	return embeddedServer(Netty,
		host = config.host,
		port = config.port,
		configure = {

			// print out the engine configuration
			println("""
				|Starting Netty engine with these settings:
				|    Number of available processors:  $parallelism
				|            Connection thread pool:  $connectionGroupSize threads
				|                Worker thread pool:  $workerGroupSize threads
				|               Request thread pool:  $callGroupSize threads
				|   Worker and Request pools shared:  $shareWorkGroup
				|               Request queue limit:  $requestQueueLimit
				|         Request concurrency limit:  $runningLimit
				|            Response write timeout:  $responseWriteTimeoutSeconds s
				|              Request read timeout:  $requestReadTimeoutSeconds s
			""".trimMargin())

			// DEBUG
			//println("default executor = ${Dispatchers.Default.asExecutor()}")
			// executor = DefaultDispatcher@6293abcc[
			//   Pool Size {core = 4, max = 512},
			//   Worker States {CPU = 0, blocking = 0, parked = 0, dormant = 0, terminated = 0},
			//   running workers queues = [],
			//   global CPU queue size = 0,
			//   global blocking queue size = 0,
			//   Control State {created workers= 0, blocking tasks = 0, CPUs acquired = 0}
			// ]
		}
	) init@{

		// sometimes the Netty adapter wants to call this init function more than once
		// but the app main was only designed to be called once,
		// so filter out the extra calls and hope nothing bad happens on the Netty side of things
		if (initialized) {
			return@init
		}
		initialized = true

		this.main(config)

	}.start(wait)
}


fun Application.main(config: Config.Web) {

	install(Compression)
	// listen to headers forwarded from the Apache reverse-proxy
	install(ForwardedHeaderSupport)
	install(Sessions) {
		cookie<User.Session>("nextPYP") {
			cookie.path = "/"
			cookie.httpOnly = false // allow browser javascript to read the cookie
			cookie.extensions["SameSite"] = "Strict" // don't send cookies to third-party sites
		}
	}
	install(WebSockets)
	install(ConditionalHeaders)

	// allocate space for cached static resources
	Resources.init(javaClass)
	val jsBundleBytes = lazy { Resources.readBytes("/assets/main.bundle.js") }
	val jsBundleBytesGzip = lazy { gzip(jsBundleBytes.value) }

	routing {

		// try to auth the user before loading the html page,
		// so we get session cookies before any js code runs
		get("/") {
			call.auth()
			// NOTE: the `assets` package is some magic package created by KVision somehow out of the src/frontendMain/web folder
			call.respondText(Resources.readText("/assets/index.html"), ContentType.Text.Html)
		}

		// serve the bundle js pre-compressed and directly out of memory, since it's large and gets hit a lot
		get("/main.bundle.js") {
			if (call.request.supportsGzip()) {
				// serve the compressed bundle
				call.attributes.put(Compression.SuppressionAttribute, true)
				call.response.headers.append(HttpHeaders.ContentEncoding, "gzip")
				call.respondBytes(jsBundleBytesGzip.value, ContentType.Application.OctetStream)
			} else {
				// serve the uncompressed bundle
				call.respondBytes(jsBundleBytes.value, ContentType.Application.OctetStream)
			}
		}

		// the SVGZ resources need extra headers
		get(ImageType.Svgz.placeholderUrl()) {
			ImageType.Svgz.respondPlaceholder(call)
		}

		// serve static resources out of KVison's magic `assets` package
		static("/") {
			resources("assets")
		}

		// add the KVision JSON-RPC routes
		applyRoutes(AdminServiceManager)
		applyRoutes(ProjectsServiceManager)
		applyRoutes(JobsServiceManager)
		applyRoutes(ClusterJobsServiceManager)
		applyRoutes(SingleParticleRawDataServiceManager)
		applyRoutes(SingleParticleRelionDataServiceManager)
		applyRoutes(SingleParticleImportDataServiceManager)
		applyRoutes(SingleParticleSessionDataServiceManager)
		applyRoutes(SingleParticlePreprocessingServiceManager)
		applyRoutes(SingleParticlePurePreprocessingServiceManager)
		applyRoutes(SingleParticleDenoisingServiceManager)
		applyRoutes(SingleParticlePickingServiceManager)
		applyRoutes(SingleParticleDrgnServiceManager)
		applyRoutes(IntegratedRefinementServiceManager)
		applyRoutes(SingleParticleCoarseRefinementServiceManager)
		applyRoutes(SingleParticleFineRefinementServiceManager)
		applyRoutes(SingleParticleFlexibleRefinementServiceManager)
		applyRoutes(SingleParticlePostprocessingServiceManager)
		applyRoutes(SingleParticleMaskingServiceManager)
		applyRoutes(TomographyRawDataServiceManager)
		applyRoutes(TomographyRelionDataServiceManager)
		applyRoutes(TomographyImportDataServiceManager)
		applyRoutes(TomographyImportDataPureServiceManager)
		applyRoutes(TomographySessionDataServiceManager)
		applyRoutes(TomographyPreprocessingServiceManager)
		applyRoutes(TomographyPurePreprocessingServiceManager)
		applyRoutes(TomographyDenoisingTrainingServiceManager)
		applyRoutes(TomographyDenoisingEvalServiceManager)
		applyRoutes(TomographyPickingServiceManager)
		applyRoutes(TomographySegmentationOpenServiceManager)
		applyRoutes(TomographySegmentationClosedServiceManager)
		applyRoutes(TomographyPickingOpenServiceManager)
		applyRoutes(TomographyPickingClosedServiceManager)
		applyRoutes(TomographyMiloTrainServiceManager)
		applyRoutes(TomographyMiloEvalServiceManager)
		applyRoutes(TomographyParticlesTrainServiceManager)
		applyRoutes(TomographyParticlesEvalServiceManager)
		applyRoutes(TomographyDrgnTrainServiceManager)
		applyRoutes(TomographyDrgnEvalServiceManager)
		applyRoutes(TomographyCoarseRefinementServiceManager)
		applyRoutes(TomographyFineRefinementServiceManager)
		applyRoutes(TomographyMovieCleaningServiceManager)
		applyRoutes(TomographyFlexibleRefinementServiceManager)
		applyRoutes(FormServiceManager)
		applyRoutes(BlocksServiceManager)
		applyRoutes(SessionsServiceManager)
		applyRoutes(SingleParticleSessionServiceManager)
		applyRoutes(TomographySessionServiceManager)
		applyRoutes(ParticlesServiceManager)
		applyRoutes(AppsServiceManager)

		// other web services
		AuthService.init(this)
		PypService.init(this)
		ParticlesService.init(this)
		ImagesService.init(this)
		FormService.init(this)
		RealTimeService.init(this)
		JobsService.init(this)
		SessionsService.init(this)
		TomographySessionService.init(this)
		IntegratedRefinementService.init(this)
		TomographyDenoisingTrainingService.init(this)
		TomographyParticlesTrainService.init(this)
		TomographyMiloTrainService.init(this)
		TomographyMiloEvalService.init(this)
		TomographyDrgnTrainService.init(this)
		TomographyDrgnEvalService.init(this)

		// only enable the debug service in debug mode
		if (config.debug) {
			DebugService.init(this)
		}
	}

	// start KVision after installing our routes,
	// since it installs a catchall / route which will make all our routes return 404
	// NOTE: do our own static resource handling, since KVision doesn't do it the way we'd like
	kvisionInit(initStaticResources = false)

	// finally, call initializers for singleton/companion objects
	// TODO: instance-ize the singletons?
	Database.init()
	JobRunner.init()
	AdminService.init()
	Session.init()
	Workflows.init()
	SessionExport.init()
	AppEndpoints.init()

	// install request metrics last, so it gets events after routing does
	install(RequestMetrics)
}


fun PipelineContext<Unit,ApplicationCall>.parseSize(): ImageSize =
	ImageSize[call.parameters.getOrFail("size")]
		?: throw BadRequestException("size must be one of ${ImageSize.values().map { it.id }}")

fun PipelineContext<Unit,ApplicationCall>.parseOwnerType(): OwnerType =
	OwnerType[call.parameters.getOrFail("ownerType")]
		?: throw BadRequestException("owner type must be one of ${OwnerType.values().map { it.id }}")


fun gzip(bytes: ByteArray): ByteArray =
	ByteArrayOutputStream()
		.apply {
			GZIPOutputStream(this).use { gzip ->
				gzip.write(bytes)
			}
		}
		.toByteArray()

fun ApplicationRequest.supportsGzip(): Boolean =
	parseHeaderValue(call.request.acceptEncoding())
		.any { it.value == "*" || it.value == "gzip" }
