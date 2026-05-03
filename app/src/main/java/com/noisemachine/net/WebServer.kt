package com.noisemachine.net

import android.content.Context
import android.util.Log
import com.noisemachine.NoiseService
import com.noisemachine.audio.SoundCatalog
import com.noisemachine.state.ScheduleManager
import com.noisemachine.state.Settings
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * NanoHTTPD-based control surface. Binds to 0.0.0.0 so it's reachable
 * over Tailscale or any local network. Routes:
 *
 *   GET  /                         -> served from assets/web/index.html
 *   GET  /static/<path>            -> served from assets/web/static
 *   GET  /api/state                -> JSON snapshot of catalog, active voices, scenes, schedule
 *   POST /api/voice/activate       -> {id, volume?}
 *   POST /api/voice/deactivate     -> {id}
 *   POST /api/voice/volume         -> {id, volume}
 *   POST /api/master/volume        -> {volume}
 *   POST /api/system-volume        -> {fraction}
 *   POST /api/stop-all
 *   POST /api/pause
 *   POST /api/resume
 *   POST /api/scene/save           -> {name, masterVolume?}
 *   POST /api/scene/apply          -> {name}
 *   POST /api/scene/delete         -> {name}
 *   POST /api/schedule/upsert      -> {entry}
 *   POST /api/schedule/delete      -> {id}
 *   POST /api/settings             -> partial settings patch
 *   POST /api/upload               -> multipart file upload (field "file")
 *   POST /api/sound/delete         -> {id}
 *   GET  /api/events               -> server-sent-events stream of state snapshots
 */
class WebServer(
    private val context: Context,
    port: Int
) : NanoHTTPD("0.0.0.0", port) {

    private val tag = "WebServer"
    private val service: NoiseService? get() = NoiseService.get()

    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri.trimEnd('/').ifEmpty { "/" }
            when {
                session.method == Method.GET && (uri == "/" || uri == "/index.html") ->
                    serveAsset("web/index.html", "text/html; charset=utf-8")
                session.method == Method.GET && uri.startsWith("/static/") ->
                    serveStatic(uri.removePrefix("/static/"))
                session.method == Method.GET && uri == "/favicon.ico" ->
                    newFixedLengthResponse(Response.Status.NO_CONTENT, "image/x-icon", "")
                session.method == Method.GET && uri == "/api/state" ->
                    json(stateJson())
                session.method == Method.GET && uri == "/api/events" ->
                    serveSse()
                session.method == Method.POST && uri == "/api/voice/activate" ->
                    handleActivate(parseBody(session))
                session.method == Method.POST && uri == "/api/voice/deactivate" ->
                    handleDeactivate(parseBody(session))
                session.method == Method.POST && uri == "/api/voice/volume" ->
                    handleVoiceVolume(parseBody(session))
                session.method == Method.POST && uri == "/api/master/volume" ->
                    handleMasterVolume(parseBody(session))
                session.method == Method.POST && uri == "/api/system-volume" ->
                    handleSystemVolume(parseBody(session))
                session.method == Method.POST && uri == "/api/stop-all" -> {
                    service?.stopAllVoices(); ok()
                }
                session.method == Method.POST && uri == "/api/pause" -> {
                    service?.pausePlayback(); ok()
                }
                session.method == Method.POST && uri == "/api/resume" -> {
                    service?.resumePlayback(); ok()
                }
                session.method == Method.POST && uri == "/api/scene/save" ->
                    handleSceneSave(parseBody(session))
                session.method == Method.POST && uri == "/api/scene/apply" ->
                    handleSceneApply(parseBody(session))
                session.method == Method.POST && uri == "/api/scene/delete" ->
                    handleSceneDelete(parseBody(session))
                session.method == Method.POST && uri == "/api/schedule/upsert" ->
                    handleScheduleUpsert(parseBody(session))
                session.method == Method.POST && uri == "/api/schedule/delete" ->
                    handleScheduleDelete(parseBody(session))
                session.method == Method.POST && uri == "/api/settings" ->
                    handleSettings(parseBody(session))
                session.method == Method.POST && uri == "/api/upload" ->
                    handleUpload(session)
                session.method == Method.POST && uri == "/api/sound/delete" ->
                    handleSoundDelete(parseBody(session))
                else -> notFound()
            }
        } catch (t: Throwable) {
            Log.e(tag, "request error: ${session.uri}", t)
            error(t.message ?: "internal error")
        }
    }

    // ----- request body parsing -----

    private fun parseBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"]
        if (!raw.isNullOrBlank()) return JSONObject(raw)
        // url-encoded fallback
        val params = session.parameters
        val obj = JSONObject()
        for ((k, v) in params) obj.put(k, v.firstOrNull() ?: "")
        return obj
    }

    // ----- handlers -----

    private fun handleActivate(body: JSONObject): Response {
        val id = body.getString("id")
        val vol = if (body.has("volume")) body.getDouble("volume").toFloat() else null
        service?.activate(id, vol)
        return json(stateJson())
    }

    private fun handleDeactivate(body: JSONObject): Response {
        val id = body.getString("id")
        service?.deactivate(id)
        return json(stateJson())
    }

    private fun handleVoiceVolume(body: JSONObject): Response {
        val id = body.getString("id")
        val v = body.getDouble("volume").toFloat()
        service?.setVoiceVolume(id, v)
        return json(stateJson())
    }

    private fun handleMasterVolume(body: JSONObject): Response {
        val v = body.getDouble("volume").toFloat()
        service?.setMasterVolume(v)
        return json(stateJson())
    }

    private fun handleSystemVolume(body: JSONObject): Response {
        val frac = body.getDouble("fraction").toFloat().coerceIn(0f, 1f)
        val s = Settings.get(context)
        s.systemVolumeTarget = frac
        s.save()
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (frac * max).toInt(), 0)
        } catch (_: Throwable) {}
        return json(stateJson())
    }

    private fun handleSceneSave(body: JSONObject): Response {
        val name = body.getString("name")
        val s = Settings.get(context)
        val active = service?.activeState() ?: emptyMap()
        val voices = active.map { Settings.VoiceState(it.key, it.value) }
        val masterVolume = if (body.has("masterVolume"))
            body.getDouble("masterVolume").toFloat()
        else s.masterVolume
        val existing = s.scenes.indexOfFirst { it.name == name }
        val scene = Settings.Scene(name, voices, masterVolume)
        if (existing >= 0) s.scenes[existing] = scene else s.scenes.add(scene)
        s.save()
        return json(stateJson())
    }

    private fun handleSceneApply(body: JSONObject): Response {
        val name = body.getString("name")
        service?.applyScene(name)
        return json(stateJson())
    }

    private fun handleSceneDelete(body: JSONObject): Response {
        val name = body.getString("name")
        val s = Settings.get(context)
        s.scenes.removeAll { it.name == name }
        s.save()
        return json(stateJson())
    }

    private fun handleScheduleUpsert(body: JSONObject): Response {
        val s = Settings.get(context)
        val id = body.optString("id").ifEmpty { UUID.randomUUID().toString() }
        val days = mutableSetOf<Int>()
        val arr = body.optJSONArray("daysOfWeek") ?: JSONArray()
        for (i in 0 until arr.length()) days += arr.getInt(i)
        val entry = Settings.ScheduleEntry(
            id = id,
            enabled = body.optBoolean("enabled", true),
            daysOfWeek = days,
            hour = body.getInt("hour"),
            minute = body.getInt("minute"),
            action = Settings.ScheduleEntry.Action.valueOf(
                body.optString("action", "ACTIVATE_SCENE")
            ),
            sceneName = if (body.isNull("sceneName")) null else body.optString("sceneName").ifEmpty { null },
            durationMinutes = if (body.isNull("durationMinutes")) null
            else body.optInt("durationMinutes").takeIf { it > 0 }
        )
        val idx = s.schedule.indexOfFirst { it.id == id }
        if (idx >= 0) s.schedule[idx] = entry else s.schedule.add(entry)
        s.save()
        ScheduleManager.rescheduleAll(context)
        return json(stateJson())
    }

    private fun handleScheduleDelete(body: JSONObject): Response {
        val id = body.getString("id")
        val s = Settings.get(context)
        s.schedule.removeAll { it.id == id }
        s.save()
        ScheduleManager.rescheduleAll(context)
        return json(stateJson())
    }

    private fun handleSettings(body: JSONObject): Response {
        val s = Settings.get(context)
        var portChanged = false
        if (body.has("port")) {
            val newPort = body.getInt("port").coerceIn(1024, 65535)
            if (newPort != s.port) { s.port = newPort; portChanged = true }
        }
        if (body.has("enableDnd")) s.enableDnd = body.getBoolean("enableDnd")
        if (body.has("allowAlarmsThroughDnd")) s.allowAlarmsThroughDnd = body.getBoolean("allowAlarmsThroughDnd")
        if (body.has("autoStartOnBoot")) s.autoStartOnBoot = body.getBoolean("autoStartOnBoot")
        if (body.has("keepScreenOn")) s.keepScreenOn = body.getBoolean("keepScreenOn")
        s.save()
        if (portChanged) service?.restartWebServer()
        return json(stateJson())
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files) // populates temp file paths into files map
        val tempPath = files["file"] ?: return error("missing file field", Response.Status.BAD_REQUEST)
        val original = session.parameters["file"]?.firstOrNull() ?: "upload.bin"
        val safeName = sanitizeFilename(original)
        val dir = SoundCatalog.userSoundsDir(context)
        val target = File(dir, safeName)
        File(tempPath).copyTo(target, overwrite = true)
        return json(stateJson())
    }

    private fun handleSoundDelete(body: JSONObject): Response {
        val id = body.getString("id")
        if (!id.startsWith("user:")) return error("can only delete user uploads", Response.Status.BAD_REQUEST)
        val name = id.removePrefix("user:")
        val f = File(SoundCatalog.userSoundsDir(context), name)
        if (f.exists()) f.delete()
        service?.deactivate(id)
        return json(stateJson())
    }

    // ----- assets -----

    private fun serveAsset(path: String, mime: String): Response {
        return try {
            val ins = context.assets.open(path)
            val bytes = ins.use { it.readBytes() }
            newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
        } catch (t: Throwable) {
            notFound()
        }
    }

    private fun serveStatic(path: String): Response {
        if (path.contains("..")) return notFound()
        val mime = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
        return serveAsset("web/static/$path", mime)
    }

    // ----- SSE -----

    private fun serveSse(): Response {
        val pipe = java.io.PipedOutputStream()
        val ins: InputStream = java.io.PipedInputStream(pipe, 8 * 1024)
        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream", ins)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        resp.addHeader("X-Accel-Buffering", "no")

        Thread({
            try {
                pipe.use { out ->
                    val w = out.bufferedWriter()
                    while (!Thread.currentThread().isInterrupted) {
                        val payload = stateJson().toString()
                        w.write("data: $payload\n\n")
                        w.flush()
                        Thread.sleep(1500)
                    }
                }
            } catch (_: Throwable) {
                // client disconnected
            }
        }, "sse-${UUID.randomUUID()}").apply { isDaemon = true }.start()

        return resp
    }

    // ----- helpers -----

    private fun stateJson(): JSONObject {
        val s = Settings.get(context)
        val active = service?.activeState() ?: emptyMap()
        val activeArr = JSONArray()
        for ((id, v) in active) {
            activeArr.put(JSONObject().put("id", id).put("volume", v.toDouble()))
        }
        val scenesArr = JSONArray()
        for (sc in s.scenes) {
            val voices = JSONArray()
            for (v in sc.voices) voices.put(
                JSONObject().put("id", v.id).put("volume", v.volume.toDouble())
            )
            scenesArr.put(JSONObject()
                .put("name", sc.name)
                .put("voices", voices)
                .put("masterVolume", sc.masterVolume.toDouble()))
        }
        val scheduleArr = JSONArray()
        for (e in s.schedule) {
            val days = JSONArray()
            for (d in e.daysOfWeek.sorted()) days.put(d)
            val nextMs = ScheduleManager.nextTriggerMs(e)
            scheduleArr.put(JSONObject()
                .put("id", e.id)
                .put("enabled", e.enabled)
                .put("daysOfWeek", days)
                .put("hour", e.hour)
                .put("minute", e.minute)
                .put("action", e.action.name)
                .put("sceneName", e.sceneName ?: JSONObject.NULL)
                .put("durationMinutes", e.durationMinutes ?: JSONObject.NULL)
                .put("nextTriggerMs", nextMs ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("catalog", SoundCatalog.toJsonArray(context))
            .put("activeVoices", activeArr)
            .put("scenes", scenesArr)
            .put("schedule", scheduleArr)
            .put("masterVolume", s.masterVolume.toDouble())
            .put("systemVolumeTarget", s.systemVolumeTarget.toDouble())
            .put("paused", service?.isPaused() ?: false)
            .put("settings", JSONObject()
                .put("port", s.port)
                .put("enableDnd", s.enableDnd)
                .put("allowAlarmsThroughDnd", s.allowAlarmsThroughDnd)
                .put("autoStartOnBoot", s.autoStartOnBoot)
                .put("keepScreenOn", s.keepScreenOn)
                .put("notificationPolicyAccess", hasDndAccess())
                .put("canScheduleExactAlarms", canScheduleExact()))
            .put("now", System.currentTimeMillis())
    }

    private fun hasDndAccess(): Boolean = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.isNotificationPolicyAccessGranted
    } catch (_: Throwable) { false }

    private fun canScheduleExact(): Boolean = try {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    } catch (_: Throwable) { false }

    private fun sanitizeFilename(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
        return cleaned.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "upload.bin" }
    }

    private fun ok(): Response = json(JSONObject().put("ok", true))
    private fun json(obj: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", obj.toString())
    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
    private fun error(msg: String, status: Response.Status = Response.Status.INTERNAL_ERROR): Response =
        newFixedLengthResponse(status, "application/json", JSONObject().put("error", msg).toString())
}
