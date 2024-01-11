package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.Backend
import jcuda.CudaException
import jcuda.driver.JCudaDriver


fun countCudaGpus(): Int {

	try {

		// call the cuda APIs to get the number of devices
		JCudaDriver.setExceptionsEnabled(true)
		JCudaDriver.cuInit(0)
		val ints = IntArray(1)
		JCudaDriver.cuDeviceGetCount(ints)
		return ints[0]

	} catch (ex: UnsatisfiedLinkError) {
		Backend.log.info("CUDA drivers not available, assuming no GPUs")
		return 0
	} catch (ex: CudaException) {
		Backend.log.error("Failed to count CUDA GPUs, assuming no GPUs", ex)
		return 0
	}
}
