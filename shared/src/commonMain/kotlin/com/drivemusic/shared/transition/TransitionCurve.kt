package com.drivemusic.shared.transition

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.abs

/**
 * One automation lane of a transition: a handful of keyframes, read back as a continuous value at
 * any point `t` in 0..1.
 *
 * Linear interpolation between keyframes rather than splines: a lane is read ~30 times a second
 * over several seconds, so the difference is inaudible where it matters, while being able to
 * reason about — and draw — exactly what a lane does is worth a lot. Curvature that *is* audible,
 * chiefly equal-power volume, is expressed by placing more keyframes, not by a curve type.
 */
@Serializable(with = TransitionCurveSerializer::class)
class TransitionCurve private constructor(val keyframes: List<Keyframe>) {

    @Serializable
    data class Keyframe(
        /** Position within the transition, 0..1. */
        val t: Double,
        /**
         * Lane value. What it means depends on the lane — gain multiplier, dB, normalized filter
         * position — and each lane's own documentation says which.
         */
        val value: Double,
    )

    /**
     * The lane's value at [t], clamped at both ends — before the first keyframe it holds the first
     * value, after the last it holds the last, so a lane never has an undefined region.
     */
    fun valueAt(t: Double): Double {
        val first = keyframes.firstOrNull() ?: return 0.0
        val last = keyframes.last()
        if (t <= first.t) return first.value
        if (t >= last.t) return last.value

        var previous = first
        for (keyframe in keyframes.drop(1)) {
            if (t <= keyframe.t) {
                val span = keyframe.t - previous.t
                if (span <= 0) return keyframe.value
                val progress = (t - previous.t) / span
                return previous.value + (keyframe.value - previous.value) * progress
            }
            previous = keyframe
        }
        return last.value
    }

    /**
     * Whether this lane does anything at all — a lane holding one value can be skipped rather than
     * written to an audio node 30 times a second, and is what the editor shows as "None".
     */
    val isConstant: Boolean
        get() {
            val first = keyframes.firstOrNull() ?: return true
            return keyframes.all { abs(it.value - first.value) < 0.0001 }
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is TransitionCurve && keyframes == other.keyframes)

    override fun hashCode(): Int = keyframes.hashCode()

    override fun toString(): String = "TransitionCurve($keyframes)"

    companion object {
        /**
         * Keyframes are sorted here, once, so [valueAt] — which runs on every tick of every
         * transition — never has to. The constructor is private precisely so this is the only way
         * in and the invariant cannot be bypassed.
         */
        operator fun invoke(keyframes: List<Keyframe>): TransitionCurve =
            TransitionCurve(keyframes.sortedBy { it.t })

        fun constant(value: Double): TransitionCurve =
            TransitionCurve(listOf(Keyframe(0.0, value)))

        /** Straight line from [from] at t=0 to [to] at t=1. */
        fun ramp(from: Double, to: Double): TransitionCurve =
            TransitionCurve(listOf(Keyframe(0.0, from), Keyframe(1.0, to)))
    }
}

/**
 * Routes decoding back through the sorting factory.
 *
 * The iOS version uses its synthesized `Codable` conformance, which reconstructs `keyframes`
 * directly and skips the sort that its own initializer performs — so a stored shape whose
 * keyframes are not already in order decodes into a lane whose single forward scan in `value(at:)`
 * reads from the wrong segment for the whole transition. Nothing writes them out of order today,
 * which is exactly why it would go unnoticed.
 */
object TransitionCurveSerializer : KSerializer<TransitionCurve> {
    private val delegate = ListSerializer(TransitionCurve.Keyframe.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: TransitionCurve) =
        delegate.serialize(encoder, value.keyframes)

    override fun deserialize(decoder: Decoder): TransitionCurve =
        TransitionCurve(delegate.deserialize(decoder))
}
