package dev.routinecoach.app.data

import kotlinx.serialization.builtins.serializer
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val disclaimerAccepted: Boolean = false,
    val planStart: LocalDate? = null,
    val paused: Boolean = false,
    /** Time windows per weekday key (mon..sun); null means "use the plan defaults". */
    val timeWindows: Map<String, List<String>>? = null,
    val reminderLeadMin: Int = 15,
    val quietStart: LocalTime = LocalTime.of(22, 0),
    val quietEnd: LocalTime = LocalTime.of(7, 0),
    val dailyReminderLimit: Int = 3,
    val escalateAfterMin: Int = 15,
    val snoozeMin: Int = 15,
    val relayServer: String = "https://ntfy.sh",
    val relayTopic: String = "",
    val fontScale: Float = 1.0f,
    val highContrast: Boolean = false,
    val lastProcessedDay: LocalDate? = null,
    val lastAckPoll: Long = 0,
) {
    val relayEnabled: Boolean get() = relayTopic.isNotBlank()
}

class SettingsStore(private val context: Context) {
    private object K {
        val disclaimer = booleanPreferencesKey("disclaimer")
        val planStart = stringPreferencesKey("planStart")
        val paused = booleanPreferencesKey("paused")
        val windows = stringPreferencesKey("timeWindows")
        val lead = intPreferencesKey("reminderLead")
        val quietStart = stringPreferencesKey("quietStart")
        val quietEnd = stringPreferencesKey("quietEnd")
        val dailyLimit = intPreferencesKey("dailyLimit")
        val escalate = intPreferencesKey("escalateAfter")
        val snooze = intPreferencesKey("snooze")
        val relayServer = stringPreferencesKey("relayServer")
        val relayTopic = stringPreferencesKey("relayTopic")
        val fontScale = floatPreferencesKey("fontScale")
        val contrast = booleanPreferencesKey("highContrast")
        val lastDay = stringPreferencesKey("lastProcessedDay")
        val lastPoll = stringPreferencesKey("lastAckPoll")
    }

    private val json = Json

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun get(): AppSettings = flow.first()

    private fun Preferences.toSettings() = AppSettings(
        disclaimerAccepted = this[K.disclaimer] ?: false,
        planStart = this[K.planStart]?.let(LocalDate::parse),
        paused = this[K.paused] ?: false,
        timeWindows = this[K.windows]?.let { runCatching { json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() },
        reminderLeadMin = this[K.lead] ?: 15,
        quietStart = this[K.quietStart]?.let(LocalTime::parse) ?: LocalTime.of(22, 0),
        quietEnd = this[K.quietEnd]?.let(LocalTime::parse) ?: LocalTime.of(7, 0),
        dailyReminderLimit = this[K.dailyLimit] ?: 3,
        escalateAfterMin = this[K.escalate] ?: 15,
        snoozeMin = this[K.snooze] ?: 15,
        relayServer = this[K.relayServer] ?: "https://ntfy.sh",
        relayTopic = this[K.relayTopic] ?: "",
        fontScale = this[K.fontScale] ?: 1.0f,
        highContrast = this[K.contrast] ?: false,
        lastProcessedDay = this[K.lastDay]?.let(LocalDate::parse),
        lastAckPoll = this[K.lastPoll]?.toLongOrNull() ?: 0,
    )

    suspend fun update(f: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = f(p.toSettings())
            p[K.disclaimer] = s.disclaimerAccepted
            s.planStart?.let { p[K.planStart] = it.toString() } ?: p.remove(K.planStart)
            p[K.paused] = s.paused
            s.timeWindows?.let { p[K.windows] = json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), kotlinx.serialization.builtins.ListSerializer(String.serializer())), it) } ?: p.remove(K.windows)
            p[K.lead] = s.reminderLeadMin
            p[K.quietStart] = s.quietStart.toString()
            p[K.quietEnd] = s.quietEnd.toString()
            p[K.dailyLimit] = s.dailyReminderLimit
            p[K.escalate] = s.escalateAfterMin
            p[K.snooze] = s.snoozeMin
            p[K.relayServer] = s.relayServer
            p[K.relayTopic] = s.relayTopic
            p[K.fontScale] = s.fontScale
            p[K.contrast] = s.highContrast
            s.lastProcessedDay?.let { p[K.lastDay] = it.toString() } ?: p.remove(K.lastDay)
            p[K.lastPoll] = s.lastAckPoll.toString()
        }
    }
}
