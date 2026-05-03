package dev.drewett.noisemachine.state

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single JSON file under filesDir/settings.json. Saved on every mutation.
 */
class Settings private constructor(private val file: File) {

    data class VoiceState(val id: String, val volume: Float)
    data class Scene(val name: String, val voices: List<VoiceState>, val masterVolume: Float)
    data class ScheduleEntry(
        val id: String,
        val enabled: Boolean,
        val daysOfWeek: Set<Int>, // 1..7 ISO (1=Mon, 7=Sun)
        val hour: Int,
        val minute: Int,
        val action: Action,
        val sceneName: String? = null,
        val durationMinutes: Int? = null
    ) {
        enum class Action { ACTIVATE_SCENE, STOP }
    }

    @Volatile var port: Int = 8378
    @Volatile var masterVolume: Float = 0.6f
    @Volatile var systemVolumeTarget: Float = 0.6f // 0..1 fraction of STREAM_MUSIC max
    @Volatile var enableDnd: Boolean = true
    @Volatile var allowAlarmsThroughDnd: Boolean = true
    @Volatile var autoStartOnBoot: Boolean = true
    @Volatile var keepScreenOn: Boolean = false

    /** The currently active mixer voices (persisted so we restore after reboot/crash). */
    val activeVoices: MutableMap<String, Float> = LinkedHashMap()
    val scenes: MutableList<Scene> = mutableListOf()
    val schedule: MutableList<ScheduleEntry> = mutableListOf()

    @Synchronized
    fun save() {
        val obj = JSONObject()
        obj.put("port", port)
        obj.put("masterVolume", masterVolume.toDouble())
        obj.put("systemVolumeTarget", systemVolumeTarget.toDouble())
        obj.put("enableDnd", enableDnd)
        obj.put("allowAlarmsThroughDnd", allowAlarmsThroughDnd)
        obj.put("autoStartOnBoot", autoStartOnBoot)
        obj.put("keepScreenOn", keepScreenOn)

        val active = JSONArray()
        for ((id, v) in activeVoices) {
            active.put(JSONObject().put("id", id).put("volume", v.toDouble()))
        }
        obj.put("activeVoices", active)

        val sc = JSONArray()
        for (s in scenes) {
            val voicesArr = JSONArray()
            for (v in s.voices) {
                voicesArr.put(JSONObject().put("id", v.id).put("volume", v.volume.toDouble()))
            }
            sc.put(JSONObject()
                .put("name", s.name)
                .put("voices", voicesArr)
                .put("masterVolume", s.masterVolume.toDouble()))
        }
        obj.put("scenes", sc)

        val sched = JSONArray()
        for (e in schedule) {
            val days = JSONArray()
            for (d in e.daysOfWeek.sorted()) days.put(d)
            sched.put(JSONObject()
                .put("id", e.id)
                .put("enabled", e.enabled)
                .put("daysOfWeek", days)
                .put("hour", e.hour)
                .put("minute", e.minute)
                .put("action", e.action.name)
                .put("sceneName", e.sceneName ?: JSONObject.NULL)
                .put("durationMinutes", e.durationMinutes ?: JSONObject.NULL))
        }
        obj.put("schedule", sched)

        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(obj.toString(2))
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun loadFrom(obj: JSONObject) {
        port = obj.optInt("port", port)
        masterVolume = obj.optDouble("masterVolume", masterVolume.toDouble()).toFloat()
        systemVolumeTarget = obj.optDouble("systemVolumeTarget", systemVolumeTarget.toDouble()).toFloat()
        enableDnd = obj.optBoolean("enableDnd", enableDnd)
        allowAlarmsThroughDnd = obj.optBoolean("allowAlarmsThroughDnd", allowAlarmsThroughDnd)
        autoStartOnBoot = obj.optBoolean("autoStartOnBoot", autoStartOnBoot)
        keepScreenOn = obj.optBoolean("keepScreenOn", keepScreenOn)

        activeVoices.clear()
        val a = obj.optJSONArray("activeVoices")
        if (a != null) {
            for (i in 0 until a.length()) {
                val v = a.getJSONObject(i)
                activeVoices[v.getString("id")] = v.optDouble("volume", 1.0).toFloat()
            }
        }

        scenes.clear()
        val sc = obj.optJSONArray("scenes")
        if (sc != null) {
            for (i in 0 until sc.length()) {
                val s = sc.getJSONObject(i)
                val voicesArr = s.optJSONArray("voices") ?: JSONArray()
                val voices = mutableListOf<VoiceState>()
                for (j in 0 until voicesArr.length()) {
                    val v = voicesArr.getJSONObject(j)
                    voices += VoiceState(v.getString("id"), v.optDouble("volume", 1.0).toFloat())
                }
                scenes += Scene(
                    s.getString("name"),
                    voices,
                    s.optDouble("masterVolume", masterVolume.toDouble()).toFloat()
                )
            }
        }

        schedule.clear()
        val sched = obj.optJSONArray("schedule")
        if (sched != null) {
            for (i in 0 until sched.length()) {
                val e = sched.getJSONObject(i)
                val daysArr = e.optJSONArray("daysOfWeek") ?: JSONArray()
                val days = mutableSetOf<Int>()
                for (j in 0 until daysArr.length()) days += daysArr.getInt(j)
                schedule += ScheduleEntry(
                    id = e.getString("id"),
                    enabled = e.optBoolean("enabled", true),
                    daysOfWeek = days,
                    hour = e.getInt("hour"),
                    minute = e.getInt("minute"),
                    action = ScheduleEntry.Action.valueOf(e.optString("action", "ACTIVATE_SCENE")),
                    sceneName = if (e.isNull("sceneName")) null else e.optString("sceneName").ifEmpty { null },
                    durationMinutes = if (e.isNull("durationMinutes")) null else e.optInt("durationMinutes")
                )
            }
        }
    }

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(ctx: Context): Settings = instance ?: synchronized(this) {
            instance ?: load(ctx).also { instance = it }
        }
        private fun load(ctx: Context): Settings {
            val file = File(ctx.filesDir, "settings.json")
            val s = Settings(file)
            if (file.exists()) {
                try {
                    s.loadFrom(JSONObject(file.readText()))
                } catch (_: Throwable) {
                    // Corrupt file: keep defaults.
                }
            }
            return s
        }
    }
}
