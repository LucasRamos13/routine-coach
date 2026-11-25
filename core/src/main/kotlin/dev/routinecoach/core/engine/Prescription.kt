package dev.routinecoach.core.engine

import dev.routinecoach.core.pack.Cadence
import dev.routinecoach.core.pack.ContentPack
import dev.routinecoach.core.pack.ExerciseMode
import dev.routinecoach.core.pack.Phase
import dev.routinecoach.core.pack.Plan
import dev.routinecoach.core.pack.SessionType
import dev.routinecoach.core.pack.StretchRoutine
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** Per-exercise state changed by the progression rules. */
data class ExerciseState(
    val exerciseId: String,
    val level: Int = 0,
    val extraSets: Int = 0,
    val lastLevelChange: LocalDate? = null,
    val lastSetAdded: LocalDate? = null,
    /** Replacement chosen after recurring pain; the original exercise is not progressed while swapped. */
    val swappedTo: String? = null,
)

data class DateRange(val from: LocalDate, val to: LocalDate) {
    operator fun contains(d: LocalDate) = d >= from && d <= to
}

/** Where the user is in the plan on a given date. */
object PlanTimeline {
    fun weekIndex(planStart: LocalDate, date: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(Weeks.weekStart(planStart), Weeks.weekStart(date)).toInt(), 7).coerceAtLeast(0)

    fun phase(plan: Plan, planStart: LocalDate, date: LocalDate): Phase {
        var w = weekIndex(planStart, date)
        for (p in plan.phases) {
            val len = p.weeks ?: return p
            if (w < len) return p
            w -= len
        }
        return plan.phases.last()
    }

    fun isProgrammedDeload(plan: Plan, planStart: LocalDate, date: LocalDate): Boolean {
        val every = plan.deload.everyWeeks ?: return false
        val w = weekIndex(planStart, date)
        return w > 0 && (w + 1) % every == 0
    }

    fun isDeload(plan: Plan, planStart: LocalDate, date: LocalDate, extraDeloads: List<DateRange>): Boolean =
        isProgrammedDeload(plan, planStart, date) || extraDeloads.any { date in it }
}

data class PrescribedItem(
    val exerciseId: String,
    /** The exercise written in the plan (differs from [exerciseId] when swapped or substituted). */
    val plannedExerciseId: String,
    val name: String,
    val mode: ExerciseMode,
    val unilateral: Boolean,
    val sets: Int,
    val min: Int,
    val max: Int,
    val rirMin: Int,
    val rirMax: Int,
    val restSec: Int?,
    val cadence: Cadence?,
    val level: Int,
    val levelLabel: String,
    val note: String,
)

data class PrescribedBlock(val id: String, val items: List<PrescribedItem>, val restSec: Int) {
    val isSuperset get() = items.size > 1
}

data class Prescription(
    val sessionId: String,
    val name: String,
    val type: SessionType,
    val description: String,
    val variant: Variant,
    val phase: Phase,
    val deload: Boolean,
    val warmup: List<String>,
    val blocks: List<PrescribedBlock>,
    val stretches: StretchRoutine?,
    val durationMin: Int,
)

object Prescriber {
    fun prescribe(
        pack: ContentPack,
        sessionId: String,
        variant: Variant,
        date: LocalDate,
        planStart: LocalDate,
        states: Map<String, ExerciseState>,
        extraDeloads: List<DateRange> = emptyList(),
    ): Prescription {
        val plan = pack.plan
        val s = plan.session(sessionId) ?: error("Unknown session '$sessionId'")
        val phase = PlanTimeline.phase(plan, planStart, date)
        val deload = PlanTimeline.isDeload(plan, planStart, date, extraDeloads)
        val keep = s.shortBlocks ?: s.blocks.take(2).map { it.id }
        val blocks = s.blocks
            .filter { variant == Variant.FULL || it.id in keep }
            .map { b ->
                PrescribedBlock(b.id, b.items.map { item ->
                    val planned = item.exercise
                    val state = states[planned]
                    val actualId = state?.swappedTo ?: planned
                    val ex = pack.exercise(actualId)
                    val actualState = states[actualId]
                    val cap = item.maxSets ?: plan.rules.maxSetsPerExercise
                    var sets = ((item.sets ?: phase.sets) + (state?.extraSets ?: 0)).coerceAtMost(cap)
                    if (deload) sets = ceil(sets * plan.deload.setFactor).toInt().coerceAtLeast(1)
                    val cadence = ex.cadence?.let { c -> phase.eccentricSec?.let { c.copy(eccentricSec = maxOf(it, c.eccentricSec)) } ?: c }
                    val level = (actualState?.level ?: 0).coerceIn(0, (ex.ladder.size - 1).coerceAtLeast(0))
                    PrescribedItem(
                        exerciseId = actualId, plannedExerciseId = planned, name = ex.name, mode = ex.mode,
                        unilateral = ex.unilateral, sets = sets, min = item.min, max = item.max,
                        rirMin = phase.rirMin, rirMax = phase.rirMax, restSec = item.restSec, cadence = cadence,
                        level = level, levelLabel = ex.levelLabel(level), note = item.note,
                    )
                }, b.restSec)
            }
        val stretches = s.stretches?.let { if (variant == Variant.SHORT) it.copy(rounds = 1) else it }
        return Prescription(
            sessionId = s.id, name = s.name, type = s.type, description = s.description, variant = variant,
            phase = phase, deload = deload, warmup = s.warmup, blocks = blocks, stretches = stretches,
            durationMin = if (variant == Variant.FULL) s.durationMin else s.shortDurationMin,
        )
    }

    /** Planned weekly working sets per primary muscle for the full weekly template. */
    fun weeklyPlannedSets(
        pack: ContentPack, date: LocalDate, planStart: LocalDate, states: Map<String, ExerciseState>,
        extraDeloads: List<DateRange> = emptyList(),
    ): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (sid in pack.plan.week.values.flatten()) {
            val s = pack.plan.session(sid) ?: continue
            if (s.type != SessionType.STRENGTH) continue
            val p = prescribe(pack, sid, Variant.FULL, date, planStart, states, extraDeloads)
            for (b in p.blocks) for (i in b.items) {
                val m = pack.exercise(i.exerciseId).primaryMuscle
                out[m] = (out[m] ?: 0) + i.sets
            }
        }
        return out
    }
}
