package dev.routinecoach.core.engine

import dev.routinecoach.core.pack.Exercise
import dev.routinecoach.core.pack.Phase
import dev.routinecoach.core.pack.RuleConfig
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One logged working set. [reps] holds seconds for timed exercises. */
data class SetRecord(val reps: Int, val level: Int, val rir: Int?, val pain: Int)

/** An exercise as performed in one session. */
data class ExercisePerformance(
    val exerciseId: String,
    val date: LocalDate,
    val sets: List<SetRecord>,
    val min: Int,
    val max: Int,
    val rirMin: Int,
    val rirMax: Int,
    val deload: Boolean,
) {
    val avgReps: Double get() = if (sets.isEmpty()) 0.0 else sets.sumOf { it.reps }.toDouble() / sets.size
    val maxPain: Int get() = sets.maxOfOrNull { it.pain } ?: 0
}

/** A completed strength session. */
data class SessionPerformance(
    val sessionLogId: Long,
    val templateId: String,
    val date: LocalDate,
    val deload: Boolean,
    val exercises: List<ExercisePerformance>,
) {
    val maxPain: Int get() = exercises.maxOfOrNull { it.maxPain } ?: 0
}

/**
 * Load progression and deload rules. Reductions apply immediately; increases only take effect from the next
 * session (prescriptions are computed when a session starts) and never with recent pain.
 */
class ProgressionEngine(private val config: RuleConfig) {

    data class Context(
        val today: LocalDate,
        val phase: Phase,
        val deloadActive: Boolean,
        val paused: Boolean,
        val exercises: Map<String, Exercise>,
        val states: Map<String, ExerciseState>,
        /** Completed strength sessions, newest first. */
        val sessions: List<SessionPerformance>,
        /** Hours slept keyed by wake-up date. */
        val sleep: Map<LocalDate, Double>,
        /** Planned weekly sets per primary muscle with the current prescription. */
        val weeklyPlannedSets: Map<String, Int>,
        val muscleTargets: Map<String, Int>,
        /** Current prescribed sets per exercise, and its cap. */
        val currentSets: Map<String, Pair<Int, Int>>,
        val lastDeloadEnd: LocalDate?,
    )

    /** Evaluates all rules after a session; [exerciseIds] are the exercises of the session just finished. */
    fun evaluate(exerciseIds: Collection<String>, ctx: Context): List<Decision> {
        if (ctx.paused) return emptyList()
        val out = mutableListOf<Decision>()

        deload(ctx)?.let { out += it }
        val swapped = mutableSetOf<String>()
        for (id in exerciseIds) swap(id, ctx)?.let { out += it; swapped += id }

        if (out.any { it.rule == Rules.DELOAD } || ctx.deloadActive || !ctx.phase.autoProgression) return out
        val painBlock = recentPain(ctx, config.noIncreasePainThreshold)
        if (painBlock != null) return out

        val musclesAdded = mutableSetOf<String>()
        for (id in exerciseIds) {
            if (id in swapped) continue
            increase(id, ctx)?.let { out += it }
            addSet(id, ctx, musclesAdded)?.let { out += it }
        }
        return out
    }

    private fun history(id: String, ctx: Context): List<ExercisePerformance> =
        ctx.sessions.mapNotNull { s -> s.exercises.firstOrNull { it.exerciseId == id } }

    /** Highest pain at or above [threshold] in the last [RuleConfig.noIncreasePainDays] days, if any. */
    private fun recentPain(ctx: Context, threshold: Int): Pair<LocalDate, Int>? {
        val since = ctx.today.minusDays(config.noIncreasePainDays.toLong() - 1)
        return ctx.sessions.filter { it.date >= since && it.maxPain >= threshold }.map { it.date to it.maxPain }.maxByOrNull { it.second }
    }

    // ------------------------------------------------------------------------------------------

    fun increase(id: String, ctx: Context): Decision? {
        val ex = ctx.exercises[id] ?: return null
        val state = ctx.states[id] ?: ExerciseState(id)
        if (ctx.states.values.any { it.swappedTo != null && it.exerciseId == id }) return null
        if (state.level >= ex.ladder.size - 1) return null
        state.lastLevelChange?.let { if (ChronoUnit.DAYS.between(it, ctx.today) < 7) return null }
        val recent = history(id, ctx).filter { !it.deload }.take(config.increaseAfterSessions)
        if (recent.size < config.increaseAfterSessions) return null
        val ok = recent.all { p ->
            p.sets.isNotEmpty() && p.sets.all { it.level == state.level && it.reps >= p.max && (it.rir == null || it.rir >= p.rirMin) }
        }
        if (!ok) return null
        val next = state.level + 1
        return Decision(Rules.INCREASE, "${ex.name}: next step",
            "All sets reached the top of the range with effort on target in ${recent.size} sessions in a row. " +
                "From the next session: ${ex.levelLabel(next)}.",
            recent.mapIndexed { i, p -> "session ${p.date}" to p.sets.joinToString(" / ") { "${it.reps} reps, RIR ${it.rir ?: "-"}" } }.toMap() +
                mapOf("range" to "${recent.first().min}-${recent.first().max}", "from" to ex.levelLabel(state.level), "to" to ex.levelLabel(next)),
            listOf(Change.SetLevel(id, state.level, next)), Direction.INCREASE)
    }

    fun addSet(id: String, ctx: Context, musclesAdded: MutableSet<String>): Decision? {
        val ex = ctx.exercises[id] ?: return null
        val muscle = ex.primaryMuscle
        if (muscle in musclesAdded) return null
        val target = ctx.muscleTargets[muscle] ?: return null
        val planned = ctx.weeklyPlannedSets[muscle] ?: 0
        if (planned >= target) return null
        val (sets, cap) = ctx.currentSets[id] ?: return null
        if (sets >= minOf(cap, config.maxSetsPerExercise)) return null
        val lastAdd = ctx.states.values.filter { s -> ctx.exercises[s.exerciseId]?.primaryMuscle == muscle }.mapNotNull { it.lastSetAdded }.maxOrNull()
        if (lastAdd != null && ChronoUnit.DAYS.between(lastAdd, ctx.today) < config.addSetMuscleIntervalDays) return null
        if (recentPain(ctx, config.painFreeBelow) != null) return null
        val recent = history(id, ctx).filter { !it.deload }.take(config.addSetAfterEasySessions)
        if (recent.size < config.addSetAfterEasySessions) return null
        val easy = recent.all { p -> p.sets.isNotEmpty() && p.sets.all { it.rir != null && it.rir > p.rirMax } }
        if (!easy) return null
        val state = ctx.states[id] ?: ExerciseState(id)
        musclesAdded += muscle
        return Decision(Rules.ADD_SET, "${ex.name}: one more set",
            "Weekly volume for $muscle is below target and the last ${recent.size} sessions felt easy (reps in reserve above target), with no pain in the last ${config.noIncreasePainDays} days. From the next session: ${sets + 1} sets.",
            mapOf("muscle" to muscle, "weekly sets" to "$planned", "target" to "$target", "sets" to "$sets → ${sets + 1}") +
                recent.associate { p -> "session ${p.date}" to "RIR " + p.sets.joinToString("/") { "${it.rir}" } },
            listOf(Change.SetExtraSets(id, state.extraSets, state.extraSets + 1)), Direction.INCREASE)
    }

    fun deload(ctx: Context): Decision? {
        if (ctx.deloadActive) return null
        ctx.lastDeloadEnd?.let { if (ChronoUnit.DAYS.between(it, ctx.today) < config.minDaysBetweenDeloads) return null }
        val reasons = mutableListOf<String>()
        val data = linkedMapOf<String, String>()

        // a) performance drop in N consecutive sessions
        val normal = ctx.sessions.filter { !it.deload }
        val drops = normal.take(config.deloadDropSessions).map { s -> s to dropped(s, normal) }
        if (drops.size == config.deloadDropSessions && drops.all { it.second != null }) {
            reasons += "performance dropped in ${config.deloadDropSessions} sessions in a row"
            drops.forEach { (s, d) -> data["drop ${s.date}"] = d!! }
        }
        // b) short sleep
        val window = (0 until config.deloadShortSleepWindowDays).map { ctx.today.minusDays(it.toLong()) }
        val shortNights = window.filter { (ctx.sleep[it] ?: Double.MAX_VALUE) < config.shortSleepHours }
        if (shortNights.size >= config.deloadShortSleepNights) {
            reasons += "${shortNights.size} nights under ${Rescheduler.fmt(config.shortSleepHours)} h in the last ${window.size} days"
            data["short nights"] = shortNights.sorted().joinToString()
        }
        // c) pain in N consecutive sessions
        val painful = ctx.sessions.take(config.deloadPainSessions)
        if (painful.size == config.deloadPainSessions && painful.all { it.maxPain >= config.deloadPainThreshold }) {
            reasons += "pain ${config.deloadPainThreshold}+ in ${config.deloadPainSessions} sessions in a row"
            data["pain"] = painful.joinToString { "${it.date}: ${it.maxPain}" }
        }
        if (reasons.isEmpty()) return null
        val to = ctx.today.plusDays(6)
        return Decision(Rules.DELOAD, "Deload week started",
            "Starting today until $to every exercise uses half the sets, because ${reasons.joinToString("; ")}.",
            data + ("deload" to "${ctx.today} – $to"),
            listOf(Change.Deload(ctx.today.toString(), to.toString())), Direction.REDUCTION)
    }

    /** Describes the drop of [s] against the previous session of the same template, or null if no drop. */
    private fun dropped(s: SessionPerformance, normal: List<SessionPerformance>): String? {
        val prev = normal.dropWhile { it !== s }.drop(1).firstOrNull { it.templateId == s.templateId } ?: return null
        var comparable = 0
        val worse = mutableListOf<String>()
        for (e in s.exercises) {
            val p = prev.exercises.firstOrNull { it.exerciseId == e.exerciseId } ?: continue
            val lv = e.sets.map { it.level }.distinct()
            if (lv.size != 1 || p.sets.map { it.level }.distinct() != lv || p.sets.isEmpty() || e.sets.isEmpty()) continue
            comparable++
            if (e.avgReps < p.avgReps * (1 - config.performanceDropFraction)) {
                worse += "${e.exerciseId} ${"%.1f".format(java.util.Locale.ROOT, p.avgReps)}→${"%.1f".format(java.util.Locale.ROOT, e.avgReps)}"
            }
        }
        return if (comparable > 0 && worse.size * 2 > comparable) worse.joinToString() else null
    }

    fun swap(id: String, ctx: Context): Decision? {
        val ex = ctx.exercises[id] ?: return null
        val state = ctx.states[id] ?: ExerciseState(id)
        if (state.swappedTo != null) return null
        val recent = history(id, ctx).take(config.swapPainSessions)
        if (recent.size < config.swapPainSessions || recent.any { it.maxPain < config.jointPainThreshold }) return null
        val alt = ex.alternatives.firstOrNull { it in ctx.exercises && it != id } ?: return Decision(Rules.SWAP,
            "${ex.name}: recurring pain",
            "Pain ${config.jointPainThreshold}+ in ${recent.size} sessions in a row and the pack has no alternative. Consider skipping it and asking a professional.",
            recent.associate { "session ${it.date}" to "max pain ${it.maxPain}" }, emptyList(), Direction.NEUTRAL)
        val altName = ctx.exercises.getValue(alt).name
        return Decision(Rules.SWAP, "${ex.name} replaced by $altName",
            "Joint pain ${config.jointPainThreshold}+ in ${recent.size} sessions in a row. $altName replaces it from now on and ${ex.name} will not progress while swapped.",
            recent.associate { "session ${it.date}" to "max pain ${it.maxPain}" },
            listOf(Change.SwapExercise(id, null, alt)), Direction.REDUCTION)
    }
}
