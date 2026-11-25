package dev.routinecoach.app.data

import androidx.room.withTransaction
import dev.routinecoach.core.engine.Availability
import dev.routinecoach.core.engine.CalendarEntry
import dev.routinecoach.core.engine.DateRange
import dev.routinecoach.core.engine.EntryStatus
import dev.routinecoach.core.engine.ExercisePerformance
import dev.routinecoach.core.engine.ExerciseState
import dev.routinecoach.core.engine.PlanTimeline
import dev.routinecoach.core.engine.Prescriber
import dev.routinecoach.core.engine.Prescription
import dev.routinecoach.core.engine.ScheduleGenerator
import dev.routinecoach.core.engine.SessionPerformance
import dev.routinecoach.core.engine.SetRecord
import dev.routinecoach.core.engine.Variant
import dev.routinecoach.core.engine.Weeks
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

fun CalendarEntryEntity.toCore() = CalendarEntry(
    id = id, sessionId = sessionId, start = startTime, variant = Variant.valueOf(variant),
    status = EntryStatus.valueOf(status), manual = manual, started = started,
)

fun ExerciseStateEntity.toCore() = ExerciseState(
    exerciseId = exerciseId, level = level, extraSets = extraSets,
    lastLevelChange = lastLevelChange?.let(LocalDate::parse), lastSetAdded = lastSetAdded?.let(LocalDate::parse),
    swappedTo = swappedTo,
)

/** Calendar, logging and history queries on top of the local database. Everything stays on the device. */
class TrainingRepository(
    val db: AppDatabase,
    private val packs: PackRepository,
    private val settings: SettingsStore,
) {
    private val cal get() = db.calendar()
    private val logs get() = db.logs()

    // ------------------------------------------------------------------------------------------
    // Plan position and availability
    // ------------------------------------------------------------------------------------------

    suspend fun planStart(): LocalDate {
        settings.get().planStart?.let { return it }
        val start = Weeks.weekStart(LocalDate.now())
        settings.update { it.copy(planStart = start) }
        return start
    }

    suspend fun timeWindows(): Map<String, List<String>> =
        settings.get().timeWindows ?: packs.ready().pack.plan.timeWindows

    suspend fun availability(): Availability =
        Availability.fromKeys(timeWindows(), cal.unavailable().map { LocalDate.parse(it.date) }.toSet())

    suspend fun states(): Map<String, ExerciseState> = db.states().all().associate { it.exerciseId to it.toCore() }

    suspend fun deloadRanges(): List<DateRange> =
        db.states().deloads().map { DateRange(LocalDate.parse(it.fromDate), LocalDate.parse(it.toDate)) }

    suspend fun isDeload(date: LocalDate): Boolean =
        PlanTimeline.isDeload(packs.ready().pack.plan, planStart(), date, deloadRanges())

    suspend fun prescription(sessionId: String, variant: Variant, date: LocalDate): Prescription =
        Prescriber.prescribe(packs.ready().pack, sessionId, variant, date, planStart(), states(), deloadRanges())

    // ------------------------------------------------------------------------------------------
    // Calendar
    // ------------------------------------------------------------------------------------------

    /** Generates the current and next week from the weekly template (once per week, so manual edits stick). */
    suspend fun ensureWeeks(today: LocalDate = LocalDate.now()) {
        val ready = packs.ready()
        val avail = availability()
        for (weekStart in listOf(Weeks.weekStart(today), Weeks.weekStart(today).plusWeeks(1))) {
            if (cal.isGenerated(weekStart.toString()) > 0) continue
            db.withTransaction {
                for (p in ScheduleGenerator.week(ready.pack.plan.week, weekStart, avail, ready.sessions)) {
                    if (p.start.toLocalDate() < today) continue
                    cal.insert(CalendarEntryEntity(sessionId = p.sessionId, start = p.start.toString()))
                }
                cal.markGenerated(GeneratedWeekEntity(weekStart.toString()))
            }
        }
    }

    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<CalendarEntryEntity>> =
        cal.observeRange(from.atStartOfDay().toString(), to.atStartOfDay().toString())

    fun observeUnavailable() = cal.observeUnavailable()

    /** Entries from the week before [date]'s week to the week after it (enough context for the 48 h rule). */
    suspend fun contextEntries(date: LocalDate): List<CalendarEntry> {
        val from = Weeks.weekStart(date).minusWeeks(1)
        val to = Weeks.weekStart(date).plusWeeks(2)
        return cal.range(from.atStartOfDay().toString(), to.atStartOfDay().toString()).map { it.toCore() }
    }

    suspend fun entry(id: Long) = cal.get(id)

    suspend fun updateEntry(e: CalendarEntryEntity) = cal.update(e)

    suspend fun moveEntry(id: Long, start: LocalDateTime) {
        val e = cal.get(id) ?: return
        cal.update(e.copy(start = start.toString(), manual = true))
    }

    suspend fun setStatus(id: Long, status: EntryStatus, manual: Boolean = true) {
        val e = cal.get(id) ?: return
        cal.update(e.copy(status = status.name, manual = e.manual || manual))
    }

    suspend fun setVariant(id: Long, variant: Variant) {
        val e = cal.get(id) ?: return
        cal.update(e.copy(variant = variant.name, manual = true))
    }

    suspend fun replaceSession(id: Long, sessionId: String) {
        val e = cal.get(id) ?: return
        cal.update(e.copy(sessionId = sessionId, manual = true))
    }

    /** Swaps the days/times of two entries. */
    suspend fun swapEntries(a: Long, b: Long) {
        val ea = cal.get(a) ?: return
        val eb = cal.get(b) ?: return
        db.withTransaction {
            cal.update(ea.copy(start = eb.start, manual = true))
            cal.update(eb.copy(start = ea.start, manual = true))
        }
    }

    suspend fun addEntry(sessionId: String, start: LocalDateTime): Long =
        cal.insert(CalendarEntryEntity(sessionId = sessionId, start = start.toString(), manual = true))

    suspend fun deleteEntry(id: Long) = cal.delete(id)

    suspend fun setUnavailable(date: LocalDate, unavailable: Boolean) {
        if (unavailable) cal.addUnavailable(UnavailableDayEntity(date.toString())) else cal.removeUnavailable(date.toString())
    }

    // ------------------------------------------------------------------------------------------
    // Sessions and sets
    // ------------------------------------------------------------------------------------------

    suspend fun startSession(entryId: Long?, sessionId: String, variant: Variant): Long {
        val today = LocalDate.now()
        val plan = packs.ready().pack.plan
        val phase = PlanTimeline.phase(plan, planStart(), today)
        val id = logs.insertSession(
            SessionLogEntity(
                entryId = entryId, sessionId = sessionId, variant = variant.name, phaseId = phase.id,
                deload = isDeload(today), startedAt = LocalDateTime.now().toString(),
            ),
        )
        entryId?.let { cal.get(it) }?.let { cal.update(it.copy(started = true, variant = variant.name)) }
        return id
    }

    suspend fun session(id: Long) = logs.session(id)
    suspend fun openSession() = logs.openSession()
    fun observeSessions() = logs.observeSessions()
    fun observeSets(sessionLogId: Long) = logs.observeSets(sessionLogId)
    fun observeEvents(sessionLogId: Long) = logs.observeEvents(sessionLogId)
    suspend fun sets(sessionLogId: Long) = logs.sets(sessionLogId)

    suspend fun finishSession(id: Long, outcome: String) {
        val s = logs.session(id) ?: return
        logs.updateSession(s.copy(finishedAt = LocalDateTime.now().toString(), outcome = outcome))
        if (outcome == "completed") s.entryId?.let { setStatus(it, EntryStatus.DONE, manual = false) }
    }

    suspend fun logSet(s: SetLogEntity): Long = logs.insertSet(s)
    suspend fun updateSet(s: SetLogEntity) = logs.updateSet(s)
    suspend fun deleteSet(id: Long) = logs.deleteSet(id)
    suspend fun logEvent(e: ExerciseEventEntity) = logs.insertEvent(e)

    /** Values from the last time this exercise was trained, by set index, to pre-fill the logger. */
    suspend fun previousSets(exerciseId: String, currentSession: Long): List<SetLogEntity> {
        val recent = logs.lastSetsOf(exerciseId, currentSession)
        val lastSession = recent.firstOrNull()?.sessionLogId ?: return emptyList()
        return recent.filter { it.sessionLogId == lastSession }.sortedBy { it.setIndex }
    }

    fun observeSetsBetween(from: LocalDate, to: LocalDate) =
        logs.observeSetsBetween(from.atStartOfDay().toString(), to.atStartOfDay().toString())

    /** Completed strength sessions, newest first, as the progression engine sees them. */
    suspend fun sessionPerformances(limit: Int = 30): List<SessionPerformance> {
        val pack = packs.ready().pack
        val sessions = logs.completedSessions(limit)
        if (sessions.isEmpty()) return emptyList()
        val sets = logs.setsFor(sessions.map { it.id }).groupBy { it.sessionLogId }
        return sessions.map { s ->
            val date = LocalDateTime.parse(s.startedAt).toLocalDate()
            val byEx = sets[s.id].orEmpty().filter { pack.exercises[it.exerciseId] != null }.groupBy { it.exerciseId }
            SessionPerformance(s.id, s.sessionId, date, s.deload, byEx.map { (ex, list) ->
                val f = list.first()
                ExercisePerformance(ex, date, list.sortedBy { it.setIndex }.map { SetRecord(it.reps, it.level, it.rir, it.pain) },
                    f.rangeMin, f.rangeMax, f.rirMin, f.rirMax, s.deload)
            })
        }
    }

    // ------------------------------------------------------------------------------------------
    // Body metrics
    // ------------------------------------------------------------------------------------------

    suspend fun logBody(m: BodyMetricEntity) = db.body().insert(m)
    suspend fun deleteBody(id: Long) = db.body().delete(id)
    fun observeBody(type: String) = db.body().observeType(type)
    fun observeRecentBody() = db.body().observeRecent(30)

    suspend fun sleepMap(days: Long = 14): Map<LocalDate, Double> =
        db.body().since("sleep", LocalDate.now().minusDays(days).toString())
            .groupBy { LocalDate.parse(it.date) }.mapValues { e -> e.value.last().value }
}
