package dev.drewett.noisemachine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.drewett.noisemachine.state.ScheduleManager
import dev.drewett.noisemachine.state.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = Settings.get(context)
        if (settings.autoStartOnBoot) {
            NoiseService.start(context)
        }
        // Re-arm alarms even if not auto-starting playback - the alarm triggers can start the service.
        ScheduleManager.rescheduleAll(context)
    }
}
