package dev.routinecoach.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.routinecoach.app.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "dev.routinecoach.REMINDER_FIRE"
        const val ACTION_ESCALATE = "dev.routinecoach.REMINDER_ESCALATE"
        const val ACTION_ACK = "dev.routinecoach.REMINDER_ACK"
        const val ACTION_SNOOZE = "dev.routinecoach.REMINDER_SNOOZE"
        const val ACTION_UNDO = "dev.routinecoach.AUTOMATION_UNDO"
        const val EXTRA_ID = "id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val pending = goAsync()
        val c = context.container
        scope.launch {
            try {
                c.packs.ready()
                when (intent.action) {
                    ACTION_FIRE -> c.reminders.fire(id)
                    ACTION_ESCALATE -> c.reminders.escalate(id)
                    ACTION_ACK -> c.reminders.acknowledge(id, "done", "phone")
                    ACTION_SNOOZE -> c.reminders.acknowledge(id, "snooze", "phone")
                    ACTION_UNDO -> c.automation.undo(id)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Background.schedule(context)
        Background.runNow(context)
    }
}
