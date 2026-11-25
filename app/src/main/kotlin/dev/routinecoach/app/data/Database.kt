package dev.routinecoach.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

/** Where a data point came from. A future Health Connect import only adds a new value, no destructive migration. */
object Source {
    const val MANUAL = "manual"
    const val HEALTH_CONNECT = "health_connect"
}

@Serializable
@Entity(tableName = "calendar_entry", indices = [Index("start")])
data class CalendarEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** ISO local date-time. */
    val start: String,
    val variant: String = "FULL",
    val status: String = "PLANNED",
    val manual: Boolean = false,
    val started: Boolean = false,
    val plannedSessionId: String = sessionId,
    val plannedStart: String = start,
) {
    val startTime: LocalDateTime get() = LocalDateTime.parse(start)
    val date: LocalDate get() = startTime.toLocalDate()
}

@Serializable
@Entity(tableName = "generated_week")
data class GeneratedWeekEntity(@PrimaryKey val weekStart: String)

@Serializable
@Entity(tableName = "session_log", indices = [Index("startedAt")])
data class SessionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long?,
    val sessionId: String,
    val variant: String,
    val phaseId: String,
    val deload: Boolean,
    val startedAt: String,
    val finishedAt: String? = null,
    /** "completed", "stopped" (warning signs) or "abandoned". */
    val outcome: String? = null,
    val source: String = Source.MANUAL,
)

@Serializable
@Entity(tableName = "set_log", indices = [Index("sessionLogId"), Index("exerciseId")])
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionLogId: Long,
    val exerciseId: String,
    val plannedExerciseId: String,
    val blockId: String,
    val setIndex: Int,
    /** Repetitions, or seconds for timed exercises. */
    val reps: Int,
    val level: Int,
    val rir: Int?,
    val pain: Int,
    val rangeMin: Int,
    val rangeMax: Int,
    val rirMin: Int,
    val rirMax: Int,
    val loggedAt: String,
    val source: String = Source.MANUAL,
)

@Serializable
@Entity(tableName = "exercise_event", indices = [Index("sessionLogId")])
data class ExerciseEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionLogId: Long,
    val exerciseId: String,
    /** "skip" or "substitute". */
    val type: String,
    val replacementId: String?,
    /** "pain", "equipment" or "time". */
    val reason: String,
    val at: String,
)

@Serializable
@Entity(tableName = "body_metric", indices = [Index("type", "date")])
data class BodyMetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    /** weight (kg), sleep (h), steps, protein (g), measurement (cm, with [name]). */
    val type: String,
    val name: String? = null,
    val value: Double,
    val unit: String,
    val recordedAt: String,
    val source: String = Source.MANUAL,
)

@Serializable
@Entity(tableName = "exercise_state")
data class ExerciseStateEntity(
    @PrimaryKey val exerciseId: String,
    val level: Int = 0,
    val extraSets: Int = 0,
    val lastLevelChange: String? = null,
    val lastSetAdded: String? = null,
    val swappedTo: String? = null,
)

@Serializable
@Entity(tableName = "automation_change", indices = [Index("createdAt")])
data class AutomationChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: String,
    val rule: String,
    val title: String,
    val reason: String,
    /** JSON object with the data the rule used. */
    val dataJson: String,
    /** JSON list of [dev.routinecoach.core.engine.Change]. */
    val changesJson: String,
    val direction: String,
    val countsAsReschedule: Boolean,
    val undoUntil: String,
    val undoneAt: String? = null,
)

@Serializable
@Entity(tableName = "deload_range")
data class DeloadRangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromDate: String,
    val toDate: String,
    /** "auto" or "manual". */
    val origin: String,
)

@Serializable
@Entity(tableName = "unavailable_day")
data class UnavailableDayEntity(@PrimaryKey val date: String)

@Serializable
@Entity(tableName = "reminder", indices = [Index("entryId", unique = true)])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val dueAt: String,
    /** PENDING, ACKED, SNOOZED, CANCELLED. A single state shared by every channel. */
    val state: String = "PENDING",
    val escalated: Boolean = false,
    val ackChannel: String? = null,
    val updatedAt: String,
)

// ---------------------------------------------------------------------------------------------

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_entry WHERE start >= :from AND start < :to ORDER BY start")
    fun observeRange(from: String, to: String): Flow<List<CalendarEntryEntity>>

    @Query("SELECT * FROM calendar_entry WHERE start >= :from AND start < :to ORDER BY start")
    suspend fun range(from: String, to: String): List<CalendarEntryEntity>

    @Query("SELECT * FROM calendar_entry WHERE id = :id")
    suspend fun get(id: Long): CalendarEntryEntity?

    @Insert suspend fun insert(e: CalendarEntryEntity): Long
    @Update suspend fun update(e: CalendarEntryEntity)

    @Query("DELETE FROM calendar_entry WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM generated_week WHERE weekStart = :weekStart")
    suspend fun isGenerated(weekStart: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markGenerated(w: GeneratedWeekEntity)

    @Query("SELECT * FROM unavailable_day")
    fun observeUnavailable(): Flow<List<UnavailableDayEntity>>

    @Query("SELECT * FROM unavailable_day")
    suspend fun unavailable(): List<UnavailableDayEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addUnavailable(d: UnavailableDayEntity)

    @Query("DELETE FROM unavailable_day WHERE date = :date")
    suspend fun removeUnavailable(date: String)

    @Query("SELECT * FROM calendar_entry") suspend fun all(): List<CalendarEntryEntity>
    @Query("SELECT * FROM generated_week") suspend fun allWeeks(): List<GeneratedWeekEntity>
}

@Dao
interface LogDao {
    @Insert suspend fun insertSession(s: SessionLogEntity): Long
    @Update suspend fun updateSession(s: SessionLogEntity)

    @Query("SELECT * FROM session_log WHERE id = :id")
    suspend fun session(id: Long): SessionLogEntity?

    @Query("SELECT * FROM session_log WHERE finishedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun openSession(): SessionLogEntity?

    @Query("SELECT * FROM session_log ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<SessionLogEntity>>

    @Query("SELECT * FROM session_log WHERE outcome = 'completed' ORDER BY startedAt DESC LIMIT :limit")
    suspend fun completedSessions(limit: Int): List<SessionLogEntity>

    @Insert suspend fun insertSet(s: SetLogEntity): Long
    @Update suspend fun updateSet(s: SetLogEntity)

    @Query("DELETE FROM set_log WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("SELECT * FROM set_log WHERE sessionLogId = :sessionLogId ORDER BY loggedAt")
    fun observeSets(sessionLogId: Long): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_log WHERE sessionLogId = :sessionLogId ORDER BY loggedAt")
    suspend fun sets(sessionLogId: Long): List<SetLogEntity>

    @Query("SELECT * FROM set_log WHERE sessionLogId IN (:ids) ORDER BY loggedAt")
    suspend fun setsFor(ids: List<Long>): List<SetLogEntity>

    @Query("SELECT * FROM set_log WHERE loggedAt >= :from AND loggedAt < :to")
    fun observeSetsBetween(from: String, to: String): Flow<List<SetLogEntity>>

    @Query("SELECT s.* FROM set_log s JOIN session_log l ON s.sessionLogId = l.id WHERE s.exerciseId = :exerciseId AND l.id != :excludeSession ORDER BY s.loggedAt DESC LIMIT 12")
    suspend fun lastSetsOf(exerciseId: String, excludeSession: Long): List<SetLogEntity>

    @Query("SELECT MAX(pain) FROM set_log WHERE loggedAt >= :since")
    suspend fun maxPainSince(since: String): Int?

    @Insert suspend fun insertEvent(e: ExerciseEventEntity): Long

    @Query("SELECT * FROM exercise_event WHERE sessionLogId = :sessionLogId")
    fun observeEvents(sessionLogId: Long): Flow<List<ExerciseEventEntity>>

    @Query("SELECT * FROM session_log") suspend fun allSessions(): List<SessionLogEntity>
    @Query("SELECT * FROM set_log") suspend fun allSets(): List<SetLogEntity>
    @Query("SELECT * FROM exercise_event") suspend fun allEvents(): List<ExerciseEventEntity>
}

@Dao
interface BodyDao {
    @Insert suspend fun insert(m: BodyMetricEntity): Long
    @Update suspend fun update(m: BodyMetricEntity)

    @Query("DELETE FROM body_metric WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM body_metric WHERE type = :type ORDER BY date")
    fun observeType(type: String): Flow<List<BodyMetricEntity>>

    @Query("SELECT * FROM body_metric WHERE type = :type AND date >= :from ORDER BY date")
    suspend fun since(type: String, from: String): List<BodyMetricEntity>

    @Query("SELECT * FROM body_metric ORDER BY recordedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BodyMetricEntity>>

    @Query("SELECT * FROM body_metric") suspend fun all(): List<BodyMetricEntity>
}

@Dao
interface StateDao {
    @Query("SELECT * FROM exercise_state")
    suspend fun all(): List<ExerciseStateEntity>

    @Query("SELECT * FROM exercise_state")
    fun observeAll(): Flow<List<ExerciseStateEntity>>

    @Query("SELECT * FROM exercise_state WHERE exerciseId = :id")
    suspend fun get(id: String): ExerciseStateEntity?

    @Upsert suspend fun upsert(s: ExerciseStateEntity)

    @Insert suspend fun insertChange(c: AutomationChangeEntity): Long
    @Update suspend fun updateChange(c: AutomationChangeEntity)

    @Query("SELECT * FROM automation_change WHERE id = :id")
    suspend fun change(id: Long): AutomationChangeEntity?

    @Query("SELECT * FROM automation_change ORDER BY createdAt DESC")
    fun observeChanges(): Flow<List<AutomationChangeEntity>>

    @Query("SELECT * FROM automation_change WHERE createdAt >= :from")
    suspend fun changesSince(from: String): List<AutomationChangeEntity>

    @Query("SELECT * FROM automation_change") suspend fun allChanges(): List<AutomationChangeEntity>

    @Insert suspend fun insertDeload(d: DeloadRangeEntity): Long

    @Query("DELETE FROM deload_range WHERE fromDate = :from AND toDate = :to")
    suspend fun deleteDeload(from: String, to: String)

    @Query("SELECT * FROM deload_range ORDER BY fromDate")
    fun observeDeloads(): Flow<List<DeloadRangeEntity>>

    @Query("SELECT * FROM deload_range ORDER BY fromDate")
    suspend fun deloads(): List<DeloadRangeEntity>
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder WHERE entryId = :entryId")
    suspend fun forEntry(entryId: Long): ReminderEntity?

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    @Upsert suspend fun upsert(r: ReminderEntity): Long

    @Query("SELECT * FROM reminder WHERE state IN ('PENDING','SNOOZED') ORDER BY dueAt")
    suspend fun active(): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE escalated = 1 AND state IN ('PENDING','SNOOZED')")
    suspend fun escalatedActive(): List<ReminderEntity>

    @Query("SELECT * FROM reminder") suspend fun all(): List<ReminderEntity>
}

@Dao
interface BackupDao {
    @Query("DELETE FROM calendar_entry") suspend fun clearCalendar()
    @Query("DELETE FROM generated_week") suspend fun clearWeeks()
    @Query("DELETE FROM session_log") suspend fun clearSessions()
    @Query("DELETE FROM set_log") suspend fun clearSets()
    @Query("DELETE FROM exercise_event") suspend fun clearEvents()
    @Query("DELETE FROM body_metric") suspend fun clearBody()
    @Query("DELETE FROM exercise_state") suspend fun clearStates()
    @Query("DELETE FROM automation_change") suspend fun clearChanges()
    @Query("DELETE FROM deload_range") suspend fun clearDeloads()
    @Query("DELETE FROM unavailable_day") suspend fun clearUnavailable()
    @Query("DELETE FROM reminder") suspend fun clearReminders()

    @Insert suspend fun insertCalendar(v: List<CalendarEntryEntity>)
    @Insert suspend fun insertWeeks(v: List<GeneratedWeekEntity>)
    @Insert suspend fun insertSessions(v: List<SessionLogEntity>)
    @Insert suspend fun insertSets(v: List<SetLogEntity>)
    @Insert suspend fun insertEvents(v: List<ExerciseEventEntity>)
    @Insert suspend fun insertBody(v: List<BodyMetricEntity>)
    @Insert suspend fun insertStates(v: List<ExerciseStateEntity>)
    @Insert suspend fun insertChanges(v: List<AutomationChangeEntity>)
    @Insert suspend fun insertDeloads(v: List<DeloadRangeEntity>)
    @Insert suspend fun insertUnavailable(v: List<UnavailableDayEntity>)
    @Query("SELECT * FROM deload_range") suspend fun allDeloads(): List<DeloadRangeEntity>
}

@Database(
    entities = [
        CalendarEntryEntity::class, GeneratedWeekEntity::class, SessionLogEntity::class, SetLogEntity::class,
        ExerciseEventEntity::class, BodyMetricEntity::class, ExerciseStateEntity::class, AutomationChangeEntity::class,
        DeloadRangeEntity::class, UnavailableDayEntity::class, ReminderEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun calendar(): CalendarDao
    abstract fun logs(): LogDao
    abstract fun body(): BodyDao
    abstract fun states(): StateDao
    abstract fun reminders(): ReminderDao
    abstract fun backup(): BackupDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "routine-coach.db").build()
    }
}
