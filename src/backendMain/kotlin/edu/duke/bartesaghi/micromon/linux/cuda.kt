package edu.duke.bartesaghi.micromon.linux

import jcuda.CudaException
import jcuda.driver.JCudaDriver
import org.slf4j.LoggerFactory


fun countCudaGpus(): Int {

	// WARNING: This function gets called from the CLI, so don't use Backend
	//          or you will cause weird errors since Backend can't initialize itself correctly in CLI mode.

	// so make a specialized logger here, since we can't use the Backend logger
	val log = LoggerFactory.getLogger("CudaGPUs")

	try {

		// call the cuda APIs to get the number of devices
		JCudaDriver.setExceptionsEnabled(true)
		JCudaDriver.cuInit(0)
		val ints = IntArray(1)
		JCudaDriver.cuDeviceGetCount(ints)
		return ints[0]

	} catch (ex: UnsatisfiedLinkError) {
		log.info("CUDA drivers not available, assuming no GPUs")
		return 0
	} catch (ex: CudaException) {
		log.error("Failed to count CUDA GPUs, assuming no GPUs", ex)
		return 0
	}
}
