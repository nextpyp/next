package edu.duke.bartesaghi.micromon.pyp

import edu.duke.bartesaghi.micromon.services.ExportServiceProperty
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline


@Serializable
data class ImageDims(
	/** image width, in unbinned pixels (or voxels) */
	val width: ValueUnbinnedI,
	/** image height, in unbinned pixels (or voxels) */
	val height: ValueUnbinnedI,
	/** image depth, in unbinned pixels (or voxels) */
	val depth: ValueUnbinnedI,
	/** the binning factor, same for all dimensions */
	val binningFactor: Int,
	/** the size of one unbinned pixel (or voxel) in a micrograph (or tilt series) */
	val pixelA: ValueA
) {

	companion object {

		/**
		 * Slicing applies an extra binning factor of 2,
		 * so there are always half as many slices as z-voxels.
		 */
		const val SLICE_FACTOR = 2
	}

	@ExportServiceProperty(skip=true)
	val numSlices: Int get() =
		depth.v/binningFactor/SLICE_FACTOR
}


@Serializable(with = ValueA.Serializer::class)
@JvmInline
value class ValueA(val v: Double) {

	fun toUnbinned(pixelA: ValueA): ValueUnbinnedF =
		ValueUnbinnedF(this.v/pixelA.v)

	fun toUnbinned(dims: ImageDims): ValueUnbinnedF =
		toUnbinned(dims.pixelA)

	// the usual language functions
	override fun toString(): String = v.toString()

	// the usual math operators
	operator fun plus(rhs: ValueA) = ValueA(this.v + rhs.v)
	operator fun minus(rhs: ValueA) = ValueA(this.v - rhs.v)
	operator fun times(rhs: ValueA) = ValueA(this.v*rhs.v)
	operator fun div(rhs: ValueA) = ValueA(this.v/rhs.v)
	operator fun compareTo(rhs: ValueA) = this.v.compareTo(rhs.v)

	// extra math functions
	fun sqrt() = ValueA(kotlin.math.sqrt(v))
	fun abs() = ValueA(kotlin.math.abs(v))

	// tragically, kotlinx.serialization doesn't know how to handle inline classes, so we need a custom serializer
	companion object Serializer : KSerializer<ValueA> {
		override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ValueA", PrimitiveKind.DOUBLE)
		override fun serialize(encoder: Encoder, value: ValueA) = encoder.encodeDouble(value.v)
		override fun deserialize(decoder: Decoder) = ValueA(decoder.decodeDouble())
	}
}


@Serializable(with = ValueUnbinnedF.Serializer::class)
@JvmInline
value class ValueUnbinnedF(val v: Double) {

	fun toI(): ValueUnbinnedI =
		ValueUnbinnedI(this.v.toInt())

	fun toA(pixelA: ValueA): ValueA =
		ValueA(this.v*pixelA.v)

	fun toA(dims: ImageDims): ValueA =
		toA(dims.pixelA)

	fun toBinned(binningFactor: Int): ValueBinnedF =
		ValueBinnedF(this.v/binningFactor)

	fun toBinned(dims: ImageDims): ValueBinnedF =
		toBinned(dims.binningFactor)

	fun toNormalizedX(dims: ImageDims): Double =
		this.v/dims.width.v

	fun toNormalizedY(dims: ImageDims): Double =
		this.v/dims.height.v

	// the usual language functions
	override fun toString(): String = v.toString()

	// the usual math operators
	operator fun plus(rhs: ValueUnbinnedF) = ValueUnbinnedF(this.v + rhs.v)
	operator fun minus(rhs: ValueUnbinnedF) = ValueUnbinnedF(this.v - rhs.v)
	operator fun times(rhs: ValueUnbinnedF) = ValueUnbinnedF(this.v*rhs.v)
	operator fun div(rhs: ValueUnbinnedF) = ValueUnbinnedF(this.v/rhs.v)
	operator fun compareTo(rhs: ValueUnbinnedF) = this.v.compareTo(rhs.v)

	// extra math functions
	fun sqrt() = ValueUnbinnedF(kotlin.math.sqrt(v))
	fun abs() = ValueUnbinnedF(kotlin.math.abs(v))

	// tragically, kotlinx.serialization doesn't know how to handle inline classes, so we need a custom serializer
	companion object Serializer : KSerializer<ValueUnbinnedF> {
		override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ValueUnbinnedF", PrimitiveKind.DOUBLE)
		override fun serialize(encoder: Encoder, value: ValueUnbinnedF) = encoder.encodeDouble(value.v)
		override fun deserialize(decoder: Decoder) = ValueUnbinnedF(decoder.decodeDouble())
	}
}


@Serializable(with = ValueUnbinnedI.Serializer::class)
@JvmInline
value class ValueUnbinnedI(val v: Int) {

	fun toF(): ValueUnbinnedF =
		ValueUnbinnedF(this.v.toDouble())

	fun toBinned(binningFactor: Int): ValueBinnedI =
		ValueBinnedI(this.v/binningFactor)

	fun toBinned(dims: ImageDims): ValueBinnedI =
		toBinned(dims.binningFactor)

	// the usual language functions
	override fun toString(): String = v.toString()

	// the usual math operators
	operator fun plus(rhs: ValueUnbinnedI) = ValueUnbinnedI(this.v + rhs.v)
	operator fun minus(rhs: ValueUnbinnedI) = ValueUnbinnedI(this.v - rhs.v)
	operator fun times(rhs: ValueUnbinnedI) = ValueUnbinnedI(this.v*rhs.v)
	operator fun div(rhs: ValueUnbinnedI) = ValueUnbinnedI(this.v/rhs.v)
	operator fun compareTo(rhs: ValueUnbinnedI) = this.v.compareTo(rhs.v)

	// extra math functions
	fun abs() = ValueUnbinnedI(kotlin.math.abs(v))

	// tragically, kotlinx.serialization doesn't know how to handle inline classes, so we need a custom serializer
	companion object Serializer : KSerializer<ValueUnbinnedI> {
		override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ValueUnbinnedI", PrimitiveKind.INT)
		override fun serialize(encoder: Encoder, value: ValueUnbinnedI) = encoder.encodeInt(value.v)
		override fun deserialize(decoder: Decoder) = ValueUnbinnedI(decoder.decodeInt())
	}
}


@Serializable(with = ValueBinnedF.Serializer::class)
@JvmInline
value class ValueBinnedF(val v: Double) {

	fun toI(): ValueBinnedI =
		ValueBinnedI(this.v.toInt())

	fun toUnbinned(binningFactor: Int): ValueUnbinnedF =
		ValueUnbinnedF(this.v*binningFactor)

	fun toUnbinned(dims: ImageDims): ValueUnbinnedF =
		toUnbinned(dims.binningFactor)

	fun withExtraBinning(extraBinning: Int): ValueBinnedF =
		ValueBinnedF(this.v/extraBinning)

	fun withoutExtraBinning(extraBinning: Int): ValueBinnedF =
		ValueBinnedF(this.v*extraBinning)

	// the usual language functions
	override fun toString(): String = v.toString()

	// the usual math operators
	operator fun plus(rhs: ValueBinnedF) = ValueBinnedF(this.v + rhs.v)
	operator fun minus(rhs: ValueBinnedF) = ValueBinnedF(this.v - rhs.v)
	operator fun times(rhs: ValueBinnedF) = ValueBinnedF(this.v*rhs.v)
	operator fun div(rhs: ValueBinnedF) = ValueBinnedF(this.v/rhs.v)
	operator fun compareTo(rhs: ValueBinnedF) = this.v.compareTo(rhs.v)

	// extra math functions
	fun sqrt() = ValueBinnedF(kotlin.math.sqrt(v))
	fun abs() = ValueBinnedF(kotlin.math.abs(v))

	// tragically, kotlinx.serialization doesn't know how to handle inline classes, so we need a custom serializer
	companion object Serializer : KSerializer<ValueBinnedF> {
		override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ValueBinnedF", PrimitiveKind.DOUBLE)
		override fun serialize(encoder: Encoder, value: ValueBinnedF) = encoder.encodeDouble(value.v)
		override fun deserialize(decoder: Decoder) = ValueBinnedF(decoder.decodeDouble())
	}
}


@Serializable(with = ValueBinnedI.Serializer::class)
@JvmInline
value class ValueBinnedI(val v: Int) {

	fun toF(): ValueBinnedF =
		ValueBinnedF(this.v.toDouble())

	fun toUnbinned(binningFactor: Int): ValueUnbinnedI =
		ValueUnbinnedI(this.v*binningFactor)

	fun toUnbinned(dims: ImageDims): ValueUnbinnedI =
		toUnbinned(dims.binningFactor)

	fun withExtraBinning(extraBinning: Int): ValueBinnedI =
		ValueBinnedI(this.v/extraBinning)

	fun withoutExtraBinning(extraBinning: Int): ValueBinnedI =
		ValueBinnedI(this.v*extraBinning)

	// the usual language functions
	override fun toString(): String = v.toString()

	// the usual math operators
	operator fun plus(rhs: ValueBinnedI) = ValueBinnedI(this.v + rhs.v)
	operator fun minus(rhs: ValueBinnedI) = ValueBinnedI(this.v - rhs.v)
	operator fun times(rhs: ValueBinnedI) = ValueBinnedI(this.v*rhs.v)
	operator fun div(rhs: ValueBinnedI) = ValueBinnedI(this.v/rhs.v)
	operator fun compareTo(rhs: ValueBinnedI) = this.v.compareTo(rhs.v)

	// extra math functions
	fun abs() = ValueBinnedI(kotlin.math.abs(v))

	// tragically, kotlinx.serialization doesn't know how to handle inline classes, so we need a custom serializer
	companion object Serializer : KSerializer<ValueBinnedI> {
		override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ValueBinnedI", PrimitiveKind.INT)
		override fun serialize(encoder: Encoder, value: ValueBinnedI) = encoder.encodeInt(value.v)
		override fun deserialize(decoder: Decoder) = ValueBinnedI(decoder.decodeInt())
	}
}


fun Double.normalizedToUnbinnedX(dims: ImageDims) = ValueUnbinnedF(this*dims.width.v)
fun Double.normalizedToUnbinnedY(dims: ImageDims) = ValueUnbinnedF(this*dims.height.v)

fun Double.flipNormalized(): Double = 1.0 - this

fun Int.sliceToUnbinnedZ(dims: ImageDims) = ValueBinnedI(this*ImageDims.SLICE_FACTOR).toUnbinned(dims.binningFactor)
