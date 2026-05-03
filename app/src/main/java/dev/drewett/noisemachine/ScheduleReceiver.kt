package dev.drewett.noisemachine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.drewett.noisemachine.state.ScheduleManager

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getStringExtra(ScheduleManager.EXTRA_ENTRY_ID) ?: return
        val autoStop = intent.getBooleanExtra(ScheduleManager.EXTRA_AUTO_STOP, false)
        val service = Intent(context, NoiseService::class.java).apply {
            action = if (autoStop) NoiseService.ACTION_AUTO_STOP else NoiseService.ACTION_FIRE_SCHEDULE
            putExtra(NoiseService.EXTRA_ENTRY_ID, entryId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
