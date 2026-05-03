package dev.drewett.noisemachine.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A procedural voice produces float samples in [-1, 1] one block at a time.
 * The mixer scales by per-voice volume and sums into the master buffer.
 */
interface ProceduralVoice {
    val id: String
    fun render(out: FloatArray, frames: Int, sampleRate: Int)
}

class WhiteNoiseVoice(override val id: String = "white") : ProceduralVoice {
    private val rng = Random(System.nanoTime())
    override fun render(out: FloatArray, frames: Int, sampleRate: Int) {
        for (i in 0 until frames) out[i] = rng.nextFloat() * 2f - 1f
    }
}

/** Voss-McCartney pink noise approximation. */
class PinkNoiseVoice(override val id: String = "pink") : ProceduralVoice {
    private val rng = Random(System.nanoTime())
    private val rows = FloatArray(16)
    private var runningSum = 0f
    private var counter = 0
    override fun render(out: FloatArray, frames: Int, sampleRate: Int) {
        for (i in 0 until frames) {
            counter++
            // Find lowest set bit
            var k = 0
            var c = counter
            while (k < rows.size - 1 && (c and 1) == 0) {
                c = c shr 1
                k++
            }
            val newVal = rng.nextFloat() * 2f - 1f
            runningSum += newVal - rows[k]
            rows[k] = newVal
            // Add an extra white component for high-freq sparkle.
            val extra = (rng.nextFloat() * 2f - 1f) * 0.3f
            out[i] = ((runningSum / rows.size) + extra) * 0.7f
        }
    }
}

class BrownNoiseVoice(override val id: String = "brown") : ProceduralVoice {
    private val rng = Random(System.nanoTime())
    private var last = 0f
    override fun render(out: FloatArray, frames: Int, sampleRate: Int) {
        for (i in 0 until frames) {
            val white = rng.nextFloat() * 2f - 1f
            last = (last + 0.02f * white).coerceIn(-1f, 1f) * 0.999f
            out[i] = last * 3.5f
        }
    }
}

/**
 * Grandfather clock tick: alternating tick / tock impulses one second apart,
 * each a short damped sine burst with subtle pre-decay clack.
 */
class ClockTickVoice(override val id: String = "clock") : ProceduralVoice {
    private var sampleIdx: Long = 0
    private var tockNext = false
    override fun render(out: FloatArray, frames: Int, sampleRate: Int) {
        val period = sampleRate.toLong() // one tick per second
        for (i in 0 until frames) {
            val pos = sampleIdx % period
            val frac = pos.toFloat() / sampleRate.toFloat()
            // Burst lasts ~80ms
            val sample = if (frac < 0.08f) {
                val env = exp(-40.0 * frac).toFloat()
                val freq = if (tockNext) 220f else 320f
                env * sin(2.0 * PI * freq * frac).toFloat() * 0.85f
            } else 0f
            out[i] = sample
            sampleIdx++
            if (sampleIdx % period == 0L) tockNext = !tockNext
        }
    }
}
