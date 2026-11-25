package dev.routinecoach.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.routinecoach.app.MainActivity
import dev.routinecoach.app.R

class Notifier(private val context: Context) {
    companion object {
        const val CH_REMINDER = "reminders"
        const val CH_AUTOMATION = "automation"
        const val CH_TIMER = "timer"
        const val CH_TIMER_ALERT = "timer_alert"
        const val CH_WARNING = "warnings"

        const val ID_TIMER = 1
        const val ID_TIMER_ALERT = 2
        const val ID_ALARM_WARNING = 3
        private const val REMINDER_BASE = 10_000
        private const val AUTOMATION_BASE = 1_000_000
    }

    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sys = context.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(NotificationChannel(CH_REMINDER, "Training reminders", NotificationManager.IMPORTANCE_HIGH))
        sys.createNotificationChannel(NotificationChannel(CH_AUTOMATION, "Plan changes", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Automatic changes to the calendar and to the load, with undo."
        })
        sys.createNotificationChannel(NotificationChannel(CH_TIMER, "Running timer", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
        })
        sys.createNotificationChannel(NotificationChannel(CH_TIMER_ALERT, "Timer finished", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        })
        sys.createNotificationChannel(NotificationChannel(CH_WARNING, "Warnings", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            nm.areNotificationsEnabled()

    fun openAppIntent(route: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra("route", it) }
        }
        return PendingIntent.getActivity(context, route.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun broadcast(action: String, id: Long, requestCode: Int): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("MissingPermission")
    fun reminder(reminderId: Long, title: String, text: String) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent("today"))
            .setAutoCancel(true)
            .addAction(0, "Done", broadcast(ReminderReceiver.ACTION_ACK, reminderId, (REMINDER_BASE + reminderId * 3).toInt()))
            .addAction(0, "Snooze", broadcast(ReminderReceiver.ACTION_SNOOZE, reminderId, (REMINDER_BASE + reminderId * 3 + 1).toInt()))
            .build()
        nm.notify((REMINDER_BASE + reminderId).toInt(), n)
    }

    fun cancelReminder(reminderId: Long) = nm.cancel((REMINDER_BASE + reminderId).toInt())

    @Suppress("MissingPermission")
    fun automationNotice(changeId: Long, title: String, reason: String, undoable: Boolean) {
        if (!canNotify()) return
        val b = NotificationCompat.Builder(context, CH_AUTOMATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openAppIntent("automation"))
            .setAutoCancel(true)
        if (undoable) b.addAction(0, "Undo", broadcast(ReminderReceiver.ACTION_UNDO, changeId, (AUTOMATION_BASE + changeId).toInt()))
        nm.notify((AUTOMATION_BASE + changeId).toInt(), b.build())
    }

    fun cancelAutomationNotice(changeId: Long) = nm.cancel((AUTOMATION_BASE + changeId).toInt())

    @Suppress("MissingPermission")
    fun warning(id: Int, title: String, text: String) {
        if (!canNotify()) return
        nm.notify(id, NotificationCompat.Builder(context, CH_WARNING)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent("settings")).setAutoCancel(true).build())
    }
}
