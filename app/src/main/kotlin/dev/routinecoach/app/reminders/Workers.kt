package dev.routinecoach.app.reminders

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.routinecoach.app.container
import java.util.concurrent.TimeUnit

/** Missed-session check, week generation and reminder sync. */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        c.packs.ready()
        c.automation.dailyCheck()
        c.reminders.sync()
        return Result.success()
    }
}

/** Publishes a reminder or an acknowledgement to the relay; retried until the network is back. */
class RelayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TYPE_REMINDER = "reminder"
        const val TYPE_ACK = "ack"

        fun enqueue(context: Context, type: String, reminderId: Long, arg: String, channel: String) {
            val req = OneTimeWorkRequestBuilder<RelayWorker>()
                .setConstraints(Background.networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(Background.data("type" to type, "id" to reminderId, "arg" to arg, "channel" to channel))
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }

    override suspend fun doWork(): Result {
        val s = applicationContext.container.settings.get()
        if (!s.relayEnabled) return Result.success()
        val relay = Relay(s.relayServer, s.relayTopic)
        val id = inputData.getLong("id", -1)
        val arg = inputData.getString("arg").orEmpty()
        return try {
            when (inputData.getString("type")) {
                TYPE_REMINDER -> {
                    // Do not relay a reminder that was acknowledged while we were offline.
                    val r = applicationContext.container.repo.db.reminders().get(id)
                    if (r != null && r.state == "PENDING") relay.publishReminder(id, arg)
                }
                TYPE_ACK -> relay.publishAck(id, arg, inputData.getString("channel").orEmpty())
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 20) Result.retry() else Result.failure()
        }
    }
}

/** Polls the relay for acknowledgements from the bot or the PC while escalated reminders are open. */
class AckPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val NAME = "ack-poll"
        fun start(context: Context) {
            val req = PeriodicWorkRequestBuilder<AckPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Background.networkConstraints()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<AckPollWorker>().setConstraints(Background.networkConstraints()).build())
        }
    }

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.get()
        val open = c.repo.db.reminders().escalatedActive()
        if (!s.relayEnabled || open.isEmpty()) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(NAME)
            return Result.success()
        }
        return try {
            val now = System.currentTimeMillis() / 1000
            val acks = Relay(s.relayServer, s.relayTopic).pollAcks(s.lastAckPoll)
            for (a in acks.sortedBy { it.time }) {
                if (a.channel != "phone") c.reminders.acknowledge(a.reminderId, a.kind, a.channel)
            }
            c.settings.update { it.copy(lastAckPoll = now - 5) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
