package dev.routinecoach.app.automation

import kotlinx.serialization.builtins.serializer
import androidx.room.withTransaction
import dev.routinecoach.app.data.AutomationChangeEntity
import dev.routinecoach.app.data.DeloadRangeEntity
import dev.routinecoach.app.data.ExerciseStateEntity
import dev.routinecoach.app.data.PackRepository
import dev.routinecoach.app.data.SettingsStore
import dev.routinecoach.app.data.TrainingRepository
import dev.routinecoach.app.reminders.Notifier
import dev.routinecoach.core.engine.Change
import dev.routinecoach.core.engine.ChangeCodec
import dev.routinecoach.core.engine.Decision
import dev.routinecoach.core.engine.PlanTimeline
import dev.routinecoach.core.engine.Prescriber
import dev.routinecoach.core.engine.ProgressionEngine
import dev.routinecoach.core.engine.Rescheduler
import dev.routinecoach.core.engine.Variant
import dev.routinecoach.core.engine.Weeks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Runs the rules engine against the local data, applies its decisions, records them in the history with a 24 h
 * undo window and notifies the user. Pause mode turns every rule off.
 */
class Automation(
    private val repo: TrainingRepository,
    private val packs: PackRepository,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val onCalendarChanged: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val db get() = repo.db

    private suspend fun rescheduler(): Rescheduler {
        val r = packs.ready()
        return Rescheduler(r.pack.plan.rules, r.sessions, r.pack.plan.recoverySession)
    }

    private suspend fun reschedulesThisWeek(now: LocalDateTime): Int {
        val weekStart = Weeks.weekStart(now.toLocalDate()).atStartOfDay().toString()
        return db.states().changesSince(weekStart).count { it.countsAsReschedule && it.undoneAt == null }
    }

    private suspend fun context(date: LocalDate, now: LocalDateTime) = Rescheduler.Context(
        entries = repo.contextEntries(date),
        availability = repo.availability(),
        now = now,
        reschedulesThisWeek = reschedulesThisWeek(now),
        paused = settings.get().paused,
    )

    // ------------------------------------------------------------------------------------------
    // Triggers
    // ------------------------------------------------------------------------------------------

    /** Processes every day that ended since the last run (missed sessions) and generates upcoming weeks. */
    suspend fun dailyCheck(now: LocalDateTime = LocalDateTime.now()) = mutex.withLock {
        val today = now.toLocalDate()
        val last = settings.get().lastProcessedDay ?: today.minusDays(1)
        var day = last.plusDays(1)
        val engine = rescheduler()
        while (day < today) {
            for (d in engine.onDayEnded(day, context(day, now))) apply(d, now)
            day = day.plusDays(1)
        }
        settings.update { it.copy(lastProcessedDay = today.minusDays(1)) }
        repo.ensureWeeks(today)
        onCalendarChanged()
    }

    suspend fun onDayUnavailable(date: LocalDate, now: LocalDateTime = LocalDateTime.now()) = mutex.withLock {
        repo.setUnavailable(date, true)
        if (!settings.get().paused) {
            for (d in rescheduler().onDayUnavailable(date, context(date, now))) apply(d, now)
        }
        onCalendarChanged()
    }

    suspend fun onSleepLogged(now: LocalDateTime = LocalDateTime.now()) = mutex.withLock {
        val today = now.toLocalDate()
        rescheduler().onSleepLogged(repo.sleepMap(), today, context(today, now))?.let { apply(it, now) }
        onCalendarChanged()
    }

    suspend fun onSetLogged(exerciseId: String, pain: Int, currentEntryId: Long?, now: LocalDateTime = LocalDateTime.now()) = mutex.withLock {
        val ex = packs.ready().pack.exercises[exerciseId] ?: return@withLock
        rescheduler().onPain(ex.name, ex.muscles, pain, currentEntryId, context(now.toLocalDate(), now))?.let {
            apply(it, now)
            onCalendarChanged()
        }
    }

    /** Runs the progression rules after a completed session. */
    suspend fun onSessionFinished(sessionLogId: Long, now: LocalDateTime = LocalDateTime.now()): List<Decision> = mutex.withLock {
        val s = settings.get()
        if (s.paused) return@withLock emptyList()
        val ready = packs.ready()
        val pack = ready.pack
        val today = now.toLocalDate()
        val planStart = repo.planStart()
        val states = repo.states()
        val deloads = repo.deloadRanges()
        val exerciseIds = repo.sets(sessionLogId).map { it.exerciseId }.distinct()

        // Current sets and caps per exercise across the weekly template.
        val currentSets = HashMap<String, Pair<Int, Int>>()
        for (sid in pack.plan.week.values.flatten().distinct()) {
            val template = pack.plan.session(sid) ?: continue
            val p = Prescriber.prescribe(pack, sid, Variant.FULL, today, planStart, states, deloads)
            for (b in p.blocks) for (i in b.items) {
                val item = template.blocks.flatMap { it.items }.firstOrNull { it.exercise == i.plannedExerciseId }
                val cap = minOf(item?.maxSets ?: pack.plan.rules.maxSetsPerExercise, pack.plan.rules.maxSetsPerExercise)
                currentSets[i.exerciseId] = i.sets to cap
            }
        }
        val ctx = ProgressionEngine.Context(
            today = today,
            phase = PlanTimeline.phase(pack.plan, planStart, today),
            deloadActive = PlanTimeline.isDeload(pack.plan, planStart, today, deloads),
            paused = false,
            exercises = pack.exercises,
            states = states,
            sessions = repo.sessionPerformances(),
            sleep = repo.sleepMap(),
            weeklyPlannedSets = Prescriber.weeklyPlannedSets(pack, today, planStart, states, deloads),
            muscleTargets = pack.plan.muscleTargets,
            currentSets = currentSets,
            lastDeloadEnd = deloads.filter { it.to < today }.maxOfOrNull { it.to },
        )
        val decisions = ProgressionEngine(pack.plan.rules).evaluate(exerciseIds, ctx)
        decisions.forEach { apply(it, now) }
        decisions
    }

    // ------------------------------------------------------------------------------------------
    // Apply / undo
    // ------------------------------------------------------------------------------------------

    private suspend fun apply(d: Decision, now: LocalDateTime) {
        val hours = packs.ready().pack.plan.rules.undoWindowHours.toLong()
        val id = db.withTransaction {
            d.changes.forEach { applyChange(it, now.toLocalDate()) }
            db.states().insertChange(
                AutomationChangeEntity(
                    createdAt = now.toString(), rule = d.rule, title = d.title, reason = d.reason,
                    dataJson = Json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), d.data), changesJson = ChangeCodec.encode(d.changes),
                    direction = d.direction.name, countsAsReschedule = d.countsAsReschedule && d.changes.any { it !is Change.SetStatus },
                    undoUntil = now.plusHours(hours).toString(),
                ),
            )
        }
        notifier.automationNotice(id, d.title, d.reason, undoable = d.changes.isNotEmpty())
    }

    fun canUndo(c: AutomationChangeEntity, now: LocalDateTime = LocalDateTime.now()) =
        c.undoneAt == null && changesOf(c).isNotEmpty() && now < LocalDateTime.parse(c.undoUntil)

    suspend fun undo(changeId: Long, now: LocalDateTime = LocalDateTime.now()): Boolean = mutex.withLock {
        val c = db.states().change(changeId) ?: return@withLock false
        if (!canUndo(c, now)) return@withLock false
        db.withTransaction {
            changesOf(c).reversed().forEach { applyChange(it.inverse(), now.toLocalDate(), undo = true) }
            db.states().updateChange(c.copy(undoneAt = now.toString()))
        }
        notifier.cancelAutomationNotice(changeId)
        onCalendarChanged()
        true
    }

    private fun changesOf(c: AutomationChangeEntity) = runCatching { ChangeCodec.decode(c.changesJson) }.getOrDefault(emptyList())

    private suspend fun applyChange(change: Change, today: LocalDate, undo: Boolean = false) {
        val cal = db.calendar()
        val st = db.states()
        suspend fun state(id: String) = st.get(id) ?: ExerciseStateEntity(id)
        when (change) {
            is Change.MoveEntry -> cal.get(change.entryId)?.let { cal.update(it.copy(start = change.to)) }
            is Change.SetVariant -> cal.get(change.entryId)?.let { cal.update(it.copy(variant = change.to.name)) }
            is Change.ReplaceSession -> cal.get(change.entryId)?.let { cal.update(it.copy(sessionId = change.to)) }
            is Change.SetStatus -> cal.get(change.entryId)?.let { cal.update(it.copy(status = change.to.name)) }
            is Change.SetLevel -> state(change.exerciseId).let {
                st.upsert(it.copy(level = change.to, lastLevelChange = if (undo) it.lastLevelChange else today.toString()))
            }
            is Change.SetExtraSets -> state(change.exerciseId).let {
                st.upsert(it.copy(extraSets = change.to, lastSetAdded = if (change.to > change.from && !undo) today.toString() else it.lastSetAdded))
            }
            is Change.Deload -> if (change.remove) st.deleteDeload(change.from, change.to)
            else st.insertDeload(DeloadRangeEntity(fromDate = change.from, toDate = change.to, origin = "auto"))
            is Change.SwapExercise -> state(change.exerciseId).let { st.upsert(it.copy(swappedTo = change.to)) }
        }
    }

    /** Manual deload (programmable from the calendar screen). Not an automatic change. */
    suspend fun scheduleManualDeload(from: LocalDate) {
        db.states().insertDeload(DeloadRangeEntity(fromDate = from.toString(), toDate = from.plusDays(6).toString(), origin = "manual"))
    }
}
