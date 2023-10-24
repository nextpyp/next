package edu.duke.bartesaghi.micromon.diagram.nodes

import edu.duke.bartesaghi.micromon.diagram.Diagram
import edu.duke.bartesaghi.micromon.nodes.NodeConfig
import edu.duke.bartesaghi.micromon.pyp.ArgValuesToml
import edu.duke.bartesaghi.micromon.pyp.Args
import edu.duke.bartesaghi.micromon.services.CommonJobData
import edu.duke.bartesaghi.micromon.services.JobData
import edu.duke.bartesaghi.micromon.services.ProjectData
import edu.duke.bartesaghi.micromon.services.ServerVal
import edu.duke.bartesaghi.micromon.views.Viewport
import js.micromondiagrams.MicromonDiagrams
import kotlin.reflect.KClass


/**
 * A bridge from the common node definitions to the client-side node definitions
 */
interface NodeClientInfo {

	val config: NodeConfig
	val type: MicromonDiagrams.NodeType
	val jobClass: KClass<out JobData>?
	val urlFragment: String?

	val urlFragmentOrThrow: String get() =
		urlFragment
			?: throw NoSuchElementException("block ${config.id} has no URL fragment")

	/** create a node from nothing but new user input */
	fun showImportForm(viewport: Viewport, diagram: Diagram, project: ProjectData, copyFrom: Node?, callback: (Node) -> Unit) {
		// no implementation by default
	}

	/** create a node from new user input, and the output of another node */
	fun showUseDataForm(viewport: Viewport, diagram: Diagram, project: ProjectData, outNode: Node, input: CommonJobData.DataId, copyFrom: Node?, callback: (Node) -> Unit) {
		// no implementation by default
	}

	/** create a job on the server, optionally with an input from another node */
	suspend fun makeJob(project: ProjectData, argValues: ArgValuesToml, input: CommonJobData.DataId?): JobData

	/** load the job data from the server */
	suspend fun getJob(jobId: String): JobData

	fun makeNode(viewport: Viewport, diagram: Diagram, project: ProjectData, job: JobData): Node

	val pypArgs: ServerVal<Args>
}
