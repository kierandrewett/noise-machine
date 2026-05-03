package dev.drewett.noisemachine.state

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.drewett.noisemachine.ScheduleReceiver
import java.util.Calendar

/**
 * Translates Settings.schedule entries into AlarmManager alarms.
 * Each entry gets one repeating slot per selected day-of-week, scheduled for
 * the next future occurrence; the receiver re-arms after firing.
 */
object ScheduleManager {

    private const val TAG = "ScheduleManager"
    const val EXTRA_ENTRY_ID = "entry_id"
    const val EXTRA_AUTO_STOP = "auto_stop"

    fun rescheduleAll(ctx: Context) {
        val s = Settings.get(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Clear existing alarms by cancelling each known intent.
        for (entry in s.schedule) {
            cancel(ctx, am, entry.id, autoStop = false)
            cancel(ctx, am, entry.id, autoStop = true)
        }
        for (entry in s.schedule) {
            if (!entry.enabled) continue
            scheduleNext(ctx, am, entry)
        }
    }

    fun scheduleNext(
        ctx: Context,
        am: AlarmManager,
        entry: Settings.ScheduleEntry
    ) {
        val triggerMs = nextTriggerMs(entry) ?: return
        val pi = pendingIntent(ctx, entry.id, autoStop = false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms() ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
            Log.i(TAG, "Scheduled ${entry.id} for ${java.util.Date(triggerMs)}")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule ${entry.id}: $t")
        }
    }

    fun scheduleAutoStop(ctx: Context, entryId: String, atMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx, entryId, autoStop = true)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (t: Throwable) {
            am.set(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun cancel(ctx: Context, am: AlarmManager, entryId: String, autoStop: Boolean) {
        val pi = pendingIntent(ctx, entryId, autoStop, mutable = false, createIfMissing = false)
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    private fun pendingIntent(
        ctx: Context,
        entryId: String,
        autoStop: Boolean,
        mutable: Boolean = false,
        createIfMissing: Boolean = true
    ): PendingIntent? {
        val intent = Intent(ctx, ScheduleReceiver::class.java).apply {
            action = if (autoStop) "dev.drewett.noisemachine.AUTO_STOP" else "dev.drewett.noisemachine.FIRE"
            putExtra(EXTRA_ENTRY_ID, entryId)
            putExtra(EXTRA_AUTO_STOP, autoStop)
        }
        val flags = (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or
            (if (createIfMissing) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        val requestCode = (entryId + if (autoStop) ":stop" else ":fire").hashCode()
        return PendingIntent.getBroadcast(ctx, requestCode, intent, flags)
    }

    /** Compute the next future time-of-day matching the entry, in millis since epoch. */
    fun nextTriggerMs(entry: Settings.ScheduleEntry): Long? {
        val now = Calendar.getInstance()
        for (offset in 0..7) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, entry.hour)
                set(Calendar.MINUTE, entry.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= now.timeInMillis) continue
            if (entry.daysOfWeek.isEmpty() || entry.daysOfWeek.contains(isoDow(cal))) {
                return cal.timeInMillis
            }
        }
        return null
    }

    private fun isoDow(cal: Calendar): Int {
        // Calendar.DAY_OF_WEEK: SUN=1..SAT=7; convert to ISO MON=1..SUN=7
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7 // Sunday
        }
    }
}
