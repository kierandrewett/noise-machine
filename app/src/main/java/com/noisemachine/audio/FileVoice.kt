package com.noisemachine.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Identifies a playable audio file. The mixer/service translates this into a MediaPlayer.
 */
sealed class FileSource {
    /** A file in app private storage uploaded by the user. */
    data class User(val path: String) : FileSource()

    /** A bundled asset under assets/sounds/<name>. */
    data class Asset(val assetPath: String) : FileSource()
}

/**
 * Wraps a single MediaPlayer that loops the given audio source.
 * The service holds one of these per active file voice.
 */
class FileVoice(
    private val context: Context,
    val id: String,
    private val source: FileSource
) {
    private var player: MediaPlayer? = null
    @Volatile var volume: Float = 1f
        private set
    @Volatile private var masterMul: Float = 1f

    fun start() {
        if (player != null) return
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            when (source) {
                is FileSource.User -> {
                    val f = File(source.path)
                    if (!f.exists()) throw IllegalStateException("missing user file: ${source.path}")
                    mp.setDataSource(f.absolutePath)
                }
                is FileSource.Asset -> {
                    val afd: AssetFileDescriptor = context.assets.openFd(source.assetPath)
                    afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                }
            }
            mp.isLooping = true
            mp.prepare()
            applyVolume(mp)
            mp.start()
            player = mp
        } catch (t: Throwable) {
            Log.w("FileVoice", "start failed for $id: $t")
            try { mp.release() } catch (_: Throwable) {}
            throw t
        }
    }

    fun stop() {
        val mp = player ?: return
        player = null
        try { mp.stop() } catch (_: Throwable) {}
        try { mp.release() } catch (_: Throwable) {}
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 2f)
        player?.let { applyVolume(it) }
    }

    fun setMasterMultiplier(m: Float) {
        masterMul = m.coerceIn(0f, 2f)
        player?.let { applyVolume(it) }
    }

    fun pause() { try { player?.pause() } catch (_: Throwable) {} }
    fun resume() { try { player?.start() } catch (_: Throwable) {} }

    private fun applyVolume(mp: MediaPlayer) {
        val v = (volume * masterMul).coerceIn(0f, 1f)
        try { mp.setVolume(v, v) } catch (_: Throwable) {}
    }
}
