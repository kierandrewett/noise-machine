package dev.drewett.noisemachine.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Sums all active procedural voices into a single mono AudioTrack stream.
 * Per-voice volumes are multiplied in; master volume is applied last.
 */
class ProceduralMixer(
    private val sampleRate: Int = 44100,
    private val bufferFrames: Int = 1024
) {
    private data class Active(val voice: ProceduralVoice, var volume: Float)

    private val active = ConcurrentHashMap<String, Active>()
    @Volatile private var masterVolume: Float = 1f
    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var renderThread: Thread? = null

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(bufferFrames * 4)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()

        running = true
        renderThread = thread(name = "ProceduralMixer", isDaemon = true) {
            val mix = FloatArray(bufferFrames)
            val tmp = FloatArray(bufferFrames)
            while (running) {
                java.util.Arrays.fill(mix, 0f)
                val snapshot = active.values.toList()
                for (a in snapshot) {
                    a.voice.render(tmp, bufferFrames, sampleRate)
                    val v = a.volume
                    for (i in 0 until bufferFrames) mix[i] += tmp[i] * v
                }
                val mv = masterVolume
                for (i in 0 until bufferFrames) {
                    var s = mix[i] * mv
                    if (s > 1f) s = 1f else if (s < -1f) s = -1f
                    mix[i] = s
                }
                try {
                    track?.write(mix, 0, bufferFrames, AudioTrack.WRITE_BLOCKING)
                } catch (t: Throwable) {
                    Log.w("ProceduralMixer", "write failed: $t")
                }
            }
        }
    }

    fun stop() {
        running = false
        try { renderThread?.join(500) } catch (_: Throwable) {}
        renderThread = null
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }

    fun setMasterVolume(v: Float) { masterVolume = v.coerceIn(0f, 2f) }

    fun setVoice(voice: ProceduralVoice, volume: Float) {
        active[voice.id] = Active(voice, volume.coerceIn(0f, 2f))
    }

    fun setVolume(id: String, volume: Float) {
        active[id]?.volume = volume.coerceIn(0f, 2f)
    }

    fun remove(id: String) {
        active.remove(id)
    }

    fun isActive(id: String) = active.containsKey(id)

    fun activeIds(): Set<String> = active.keys.toSet()
}
