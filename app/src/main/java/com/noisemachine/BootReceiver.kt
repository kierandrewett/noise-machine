package com.noisemachine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.noisemachine.state.ScheduleManager
import com.noisemachine.state.Settings

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
