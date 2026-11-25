package dev.routinecoach.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.routinecoach.app.data.PackRepository
import dev.routinecoach.app.data.ReminderEntity
import dev.routinecoach.app.data.SettingsStore
import dev.routinecoach.app.data.TrainingRepository
import dev.routinecoach.core.engine.EntryStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Local reminders with a single shared state per reminder (PENDING, SNOOZED, ACKED, CANCELLED).
 * The phone notifies first; if nobody acknowledges within the escalation delay, the reminder is relayed to the
 * bot and the PC through ntfy. Acknowledging on any channel cancels the others.
 */
class ReminderScheduler(
    private val context: Context,
    private val repo: TrainingRepository,
    private val packs: PackRepository,
    private val settings: SettingsStore,
    private val notifier: Notifier,
) {
    private val dao get() = repo.db.reminders()
    private val am = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    fun exactAlarmsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** Recomputes reminders for the next 7 days and (re)arms their alarms. */
    suspend fun sync(now: LocalDateTime = LocalDateTime.now()) = mutex.withLock {
        val s = settings.get()
        packs.current() ?: return@withLock
        val entries = repo.contextEntries(now.toLocalDate()).filter { it.start > now && it.start < now.plusDays(7) }
        val perDay = HashMap<LocalDate, Int>()
        val activeIds = HashSet<Long>()
        for (e in entries.sortedBy { it.start }) {
            if (e.status != EntryStatus.PLANNED) continue
            val due = dueTime(e.start, s.reminderLeadMin, s.quietStart, s.quietEnd, now) ?: continue
            val count = perDay.getOrDefault(due.toLocalDate(), 0)
            if (count >= s.dailyReminderLimit) continue
            perDay[due.toLocalDate()] = count + 1
            val existing = dao.forEntry(e.id)
            val r = when {
                existing == null -> ReminderEntity(entryId = e.id, dueAt = due.toString(), updatedAt = now.toString())
                existing.state == "SNOOZED" -> existing
                existing.dueAt != due.toString() -> existing.copy(dueAt = due.toString(), state = "PENDING", escalated = false, ackChannel = null, updatedAt = now.toString())
                else -> existing
            }
            val id = if (r.id == 0L) dao.upsert(r) else r.also { dao.upsert(it) }.id
            activeIds += e.id
            if (r.state == "PENDING" || r.state == "SNOOZED") arm(id, LocalDateTime.parse(r.dueAt), ReminderReceiver.ACTION_FIRE, id.toInt())
        }
        // Cancel reminders whose session is gone, done or moved out of range.
        for (r in dao.active()) {
            if (r.entryId !in activeIds && LocalDateTime.parse(r.dueAt) > now) {
                val entry = repo.entry(r.entryId)
                if (entry == null || entry.status != EntryStatus.PLANNED.name) {
                    dao.upsert(r.copy(state = "CANCELLED", updatedAt = now.toString()))
                    cancelAlarm(r.id.toInt(), ReminderReceiver.ACTION_FIRE)
                    notifier.cancelReminder(r.id)
                }
            }
        }
        if (!exactAlarmsAllowed()) {
            notifier.warning(Notifier.ID_ALARM_WARNING, "Reminders may be late",
                "Exact alarms are not allowed for Routine Coach. Open Settings to allow them so reminders fire on time.")
        }
    }

    /** Due time respecting the lead time and quiet hours; null when no acceptable time exists. */
    fun dueTime(start: LocalDateTime, leadMin: Int, quietStart: LocalTime, quietEnd: LocalTime, now: LocalDateTime): LocalDateTime? {
        var due = start.minusMinutes(leadMin.toLong())
        if (due < now) due = now.plusSeconds(30)
        if (isQuiet(due.toLocalTime(), quietStart, quietEnd)) {
            // Move to the last minute before quiet hours start, if that is still in the future.
            var before = due.with(quietStart).minusMinutes(1)
            if (before > due) before = before.minusDays(1)
            due = if (before > now) before else return null
        }
        return due
    }

    private fun isQuiet(t: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (start <= end) t >= start && t < end else t >= start || t < end

    fun arm(reminderId: Long, at: LocalDateTime, action: String, requestCode: Int) {
        val pi = pending(reminderId, action, requestCode)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (exactAlarmsAllowed()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
    }

    fun cancelAlarm(requestCode: Int, action: String) {
        am.cancel(pending(0, action, requestCode))
    }

    private fun pending(reminderId: Long, action: String, requestCode: Int): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, reminderId)
        return PendingIntent.getBroadcast(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // ------------------------------------------------------------------------------------------
    // Reminder lifecycle
    // ------------------------------------------------------------------------------------------

    suspend fun fire(reminderId: Long, now: LocalDateTime = LocalDateTime.now()) {
        val r = dao.get(reminderId) ?: return
        if (r.state != "PENDING" && r.state != "SNOOZED") return
        val entry = repo.entry(r.entryId) ?: return
        if (entry.status != EntryStatus.PLANNED.name || entry.started) return
        val name = packs.current()?.sessions?.get(entry.sessionId)?.name ?: "Training"
        val time = entry.startTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        notifier.reminder(r.id, "$name at $time", "Tap Done to acknowledge or Snooze to be reminded later.")
        dao.upsert(r.copy(state = "PENDING", updatedAt = now.toString()))
        val s = settings.get()
        if (s.relayEnabled) {
            arm(r.id, now.plusMinutes(s.escalateAfterMin.toLong()), ReminderReceiver.ACTION_ESCALATE, (r.id + ESCALATE_OFFSET).toInt())
        }
    }

    suspend fun escalate(reminderId: Long, now: LocalDateTime = LocalDateTime.now()) {
        val r = dao.get(reminderId) ?: return
        if (r.state != "PENDING") return
        val entry = repo.entry(r.entryId) ?: return
        dao.upsert(r.copy(escalated = true, updatedAt = now.toString()))
        RelayWorker.enqueue(context, RelayWorker.TYPE_REMINDER, r.id, entry.startTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")), "")
        AckPollWorker.start(context)
    }

    /** Applies an acknowledgement from any channel ("phone", "ntfy", "telegram", "desktop"). */
    suspend fun acknowledge(reminderId: Long, kind: String, channel: String, now: LocalDateTime = LocalDateTime.now()) {
        val r = dao.get(reminderId) ?: return
        if (r.state == "ACKED" || r.state == "CANCELLED") return
        notifier.cancelReminder(r.id)
        cancelAlarm((r.id + ESCALATE_OFFSET).toInt(), ReminderReceiver.ACTION_ESCALATE)
        if (kind == "snooze") {
            val until = now.plusMinutes(settings.get().snoozeMin.toLong())
            dao.upsert(r.copy(state = "SNOOZED", dueAt = until.toString(), escalated = false, ackChannel = channel, updatedAt = now.toString()))
            arm(r.id, until, ReminderReceiver.ACTION_FIRE, r.id.toInt())
        } else {
            dao.upsert(r.copy(state = "ACKED", ackChannel = channel, updatedAt = now.toString()))
            cancelAlarm(r.id.toInt(), ReminderReceiver.ACTION_FIRE)
        }
        // Tell the other channels so they clear their copy.
        if (r.escalated && channel == "phone") RelayWorker.enqueue(context, RelayWorker.TYPE_ACK, r.id, kind, channel)
    }

    companion object {
        const val ESCALATE_OFFSET = 500_000L
    }
}

/** Schedules the periodic maintenance work (missed sessions, week generation, reminder sync). */
object Background {
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<DailyWorker>(3, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<DailyWorker>().build())
    }

    fun networkConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun data(vararg pairs: Pair<String, Any?>): Data = Data.Builder().apply {
        pairs.forEach { (k, v) -> when (v) { is String -> putString(k, v); is Long -> putLong(k, v); is Int -> putInt(k, v) } }
    }.build()
}
