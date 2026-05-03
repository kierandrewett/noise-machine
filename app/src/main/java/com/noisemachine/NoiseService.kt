package com.noisemachine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.noisemachine.audio.BrownNoiseVoice
import com.noisemachine.audio.ClockTickVoice
import com.noisemachine.audio.FileSource
import com.noisemachine.audio.FileVoice
import com.noisemachine.audio.PinkNoiseVoice
import com.noisemachine.audio.ProceduralMixer
import com.noisemachine.audio.ProceduralVoice
import com.noisemachine.audio.SoundCatalog
import com.noisemachine.audio.WhiteNoiseVoice
import com.noisemachine.net.WebServer
import com.noisemachine.state.ScheduleManager
import com.noisemachine.state.Settings
import java.util.concurrent.ConcurrentHashMap

class NoiseService : Service() {

    private val tag = "NoiseService"
    private lateinit var settings: Settings
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var transientLoss = false

    private val proceduralMixer = ProceduralMixer()
    private val fileVoices = ConcurrentHashMap<String, FileVoice>()
    private var webServer: WebServer? = null

    @Volatile private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings.get(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
        startForegroundCompat(buildNotification(running = false))
        acquireWakeLock()

        proceduralMixer.start()
        proceduralMixer.setMasterVolume(settings.masterVolume)

        // Restore active voices.
        for ((id, vol) in settings.activeVoices.toMap()) {
            try { activate(id, vol, persist = false) } catch (_: Throwable) {}
        }

        applyDndIfRequested()
        applySystemVolume()
        startWebServer()

        // Wire up scheduled entries.
        ScheduleManager.rescheduleAll(this)
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FIRE_SCHEDULE -> handleScheduleFire(intent.getStringExtra(EXTRA_ENTRY_ID))
            ACTION_AUTO_STOP -> stopAllVoices()
            ACTION_REQUEST_FOCUS -> ensureAudioFocus()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { webServer?.stop() } catch (_: Throwable) {}
        webServer = null
        for (v in fileVoices.values.toList()) v.stop()
        fileVoices.clear()
        proceduralMixer.stop()
        abandonFocus()
        restoreDnd()
        releaseWakeLock()
    }

    // ----- Public API used by WebServer -----

    fun activate(id: String, volume: Float? = null, persist: Boolean = true) {
        val sound = SoundCatalog.find(this, id) ?: throw IllegalArgumentException("unknown sound: $id")
        val v = (volume ?: settings.activeVoices[id] ?: 1f).coerceIn(0f, 2f)
        ensureAudioFocus()
        when (sound.kind) {
            SoundCatalog.Sound.Kind.PROCEDURAL -> {
                val voice: ProceduralVoice = when ((sound.source as SoundCatalog.Sound.Source.Procedural).type) {
                    "white" -> WhiteNoiseVoice()
                    "pink" -> PinkNoiseVoice()
                    "brown" -> BrownNoiseVoice()
                    "clock" -> ClockTickVoice()
                    else -> throw IllegalArgumentException("unknown procedural type")
                }
                proceduralMixer.setVoice(voice, v)
            }
            SoundCatalog.Sound.Kind.FILE -> {
                fileVoices[id]?.stop()
                val src = when (val s = sound.source) {
                    is SoundCatalog.Sound.Source.Asset -> FileSource.Asset(s.assetPath)
                    is SoundCatalog.Sound.Source.User -> FileSource.User(s.path)
                    else -> throw IllegalStateException()
                }
                val fv = FileVoice(this, id, src)
                fv.setMasterMultiplier(settings.masterVolume)
                fv.setVolume(v)
                fv.start()
                fileVoices[id] = fv
            }
        }
        settings.activeVoices[id] = v
        if (persist) settings.save()
        updateNotification()
    }

    fun deactivate(id: String, persist: Boolean = true) {
        proceduralMixer.remove(id)
        fileVoices.remove(id)?.stop()
        settings.activeVoices.remove(id)
        if (persist) settings.save()
        updateNotification()
    }

    fun setVoiceVolume(id: String, volume: Float) {
        val v = volume.coerceIn(0f, 2f)
        if (proceduralMixer.isActive(id)) proceduralMixer.setVolume(id, v)
        fileVoices[id]?.setVolume(v)
        if (settings.activeVoices.containsKey(id)) {
            settings.activeVoices[id] = v
            settings.save()
        }
    }

    fun setMasterVolume(v: Float) {
        val cv = v.coerceIn(0f, 2f)
        settings.masterVolume = cv
        proceduralMixer.setMasterVolume(cv)
        for (fv in fileVoices.values) fv.setMasterMultiplier(cv)
        settings.save()
    }

    fun stopAllVoices() {
        for (id in proceduralMixer.activeIds().toList()) proceduralMixer.remove(id)
        for (v in fileVoices.values.toList()) v.stop()
        fileVoices.clear()
        settings.activeVoices.clear()
        settings.save()
        abandonFocus()
        updateNotification()
    }

    fun applyScene(sceneName: String) {
        val scene = settings.scenes.firstOrNull { it.name == sceneName }
            ?: throw IllegalArgumentException("scene not found: $sceneName")
        // Stop voices not in the scene
        val keepIds = scene.voices.map { it.id }.toSet()
        for (id in (proceduralMixer.activeIds() + fileVoices.keys).toSet()) {
            if (id !in keepIds) deactivate(id, persist = false)
        }
        setMasterVolume(scene.masterVolume)
        for (v in scene.voices) activate(v.id, v.volume, persist = false)
        settings.save()
    }

    fun activeState(): Map<String, Float> {
        val m = LinkedHashMap<String, Float>()
        for (id in proceduralMixer.activeIds()) m[id] = settings.activeVoices[id] ?: 1f
        for ((id, fv) in fileVoices) m[id] = fv.volume
        return m
    }

    fun pausePlayback() {
        if (paused) return
        paused = true
        for (fv in fileVoices.values) fv.pause()
        proceduralMixer.setMasterVolume(0f)
        updateNotification()
    }

    fun resumePlayback() {
        if (!paused) return
        paused = false
        proceduralMixer.setMasterVolume(settings.masterVolume)
        for (fv in fileVoices.values) fv.resume()
        updateNotification()
    }

    fun isPaused(): Boolean = paused

    // ----- Schedule firing -----

    private fun handleScheduleFire(entryId: String?) {
        if (entryId == null) return
        val entry = settings.schedule.firstOrNull { it.id == entryId } ?: return
        try {
            when (entry.action) {
                Settings.ScheduleEntry.Action.ACTIVATE_SCENE -> {
                    entry.sceneName?.let { applyScene(it) }
                }
                Settings.ScheduleEntry.Action.STOP -> stopAllVoices()
            }
            entry.durationMinutes?.let { mins ->
                val until = System.currentTimeMillis() + mins * 60_000L
                ScheduleManager.scheduleAutoStop(this, entry.id, until)
            }
        } catch (t: Throwable) {
            Log.w(tag, "schedule fire failed: $t")
        } finally {
            // Re-arm the next occurrence.
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            ScheduleManager.scheduleNext(this, am, entry)
        }
    }

    // ----- Audio focus / DND / volume -----

    private fun ensureAudioFocus() {
        if (hasFocus) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        val res = audioManager.requestAudioFocus(req)
        hasFocus = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
        transientLoss = false
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                transientLoss = true
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (transientLoss) {
                    transientLoss = false
                    resumePlayback()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Persistent loss: keep service alive but stop sound.
                stopAllVoices()
            }
        }
    }

    private fun applyDndIfRequested() {
        if (!settings.enableDnd) return
        if (notificationManager.isNotificationPolicyAccessGranted) {
            try {
                val filter = if (settings.allowAlarmsThroughDnd)
                    NotificationManager.INTERRUPTION_FILTER_ALARMS
                else
                    NotificationManager.INTERRUPTION_FILTER_NONE
                notificationManager.setInterruptionFilter(filter)
            } catch (t: Throwable) {
                Log.w(tag, "DND apply failed: $t")
            }
        }
    }

    private fun restoreDnd() {
        if (notificationManager.isNotificationPolicyAccessGranted) {
            try { notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
            catch (_: Throwable) {}
        }
    }

    private fun applySystemVolume() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (settings.systemVolumeTarget.coerceIn(0f, 1f) * max).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (t: Throwable) {
            Log.w(tag, "set stream volume failed: $t")
        }
    }

    // ----- Wake lock -----

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noisemachine:audio").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    // ----- Web server -----

    private fun startWebServer() {
        val port = settings.port
        try {
            webServer = WebServer(this, port).also { it.start() }
            Log.i(tag, "web server started on :$port")
        } catch (t: Throwable) {
            Log.e(tag, "web server failed: $t")
        }
    }

    fun restartWebServer() {
        try { webServer?.stop() } catch (_: Throwable) {}
        webServer = null
        startWebServer()
    }

    // ----- Notification -----

    private fun ensureChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(ch)
    }

    private fun buildNotification(running: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (running)
            getString(R.string.notification_running)
        else
            getString(R.string.notification_idle)
        val activeCount = proceduralMixer.activeIds().size + fileVoices.size
        val text = if (running) "$activeCount voice${if (activeCount == 1) "" else "s"} - port ${settings.port}"
        else "Tap to open controls"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPi)
            .build()
    }

    private fun updateNotification() {
        val running = proceduralMixer.activeIds().isNotEmpty() || fileVoices.isNotEmpty()
        notificationManager.notify(NOTIFICATION_ID, buildNotification(running))
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL_ID = "noise_machine_playback"
        private const val NOTIFICATION_ID = 1729

        const val ACTION_FIRE_SCHEDULE = "com.noisemachine.action.FIRE_SCHEDULE"
        const val ACTION_AUTO_STOP = "com.noisemachine.action.AUTO_STOP"
        const val ACTION_REQUEST_FOCUS = "com.noisemachine.action.REQUEST_FOCUS"
        const val EXTRA_ENTRY_ID = "entry_id"

        @Volatile private var instance: NoiseService? = null
        fun get(): NoiseService? = instance

        fun start(ctx: Context) {
            val i = Intent(ctx, NoiseService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
