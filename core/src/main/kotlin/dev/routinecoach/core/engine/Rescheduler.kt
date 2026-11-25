package dev.routinecoach.core.engine

import dev.routinecoach.core.pack.RuleConfig
import dev.routinecoach.core.pack.SessionType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Calendar automation: moves missed sessions, redistributes unavailable days and reacts to poor sleep and pain.
 *
 * Constraints for any placement: at least [RuleConfig.minHoursBetweenSameGroup] hours between sessions that train
 * the same muscles, at most [RuleConfig.maxStrengthPerWeek] strength sessions per week and
 * [RuleConfig.maxStrengthPerDay] per day, no interval session in the [RuleConfig.noIntervalHoursBeforeLegs] hours
 * before a leg session, and only inside the user's time windows. Entries marked manual are never modified.
 */
class Rescheduler(
    private val config: RuleConfig,
    private val sessions: Map<String, SessionInfo>,
    private val recoverySession: String?,
) {
    data class Context(
        /** Entries of the affected week plus the neighbouring weeks (needed for the 48 h rule). */
        val entries: List<CalendarEntry>,
        val availability: Availability,
        val now: LocalDateTime,
        /** Automatic reschedules already applied this week (not undone). */
        val reschedulesThisWeek: Int,
        val paused: Boolean,
    )

    private fun info(e: CalendarEntry) = sessions[e.sessionId]
    private fun name(id: String) = sessions[id]?.name ?: id

    // ------------------------------------------------------------------------------------------
    // Missed sessions
    // ------------------------------------------------------------------------------------------

    /** Handles planned sessions of [day] that were never started. Call once the day is over. */
    fun onDayEnded(day: LocalDate, ctx: Context): List<Decision> {
        val missed = ctx.entries.filter { it.date == day && it.status == EntryStatus.PLANNED && !it.started }
        if (missed.isEmpty()) return emptyList()
        val out = mutableListOf<Decision>()
        var entries = ctx.entries
        var budget = config.maxAutoReschedulesPerWeek - ctx.reschedulesThisWeek
        for (e in missed) {
            val inf = info(e) ?: continue
            val markMissed = Change.SetStatus(e.id, EntryStatus.PLANNED, EntryStatus.MISSED)
            if (ctx.paused || e.manual || inf.type != SessionType.STRENGTH) {
                // Bookkeeping only: the day is over. Not an automatic reschedule.
                out += Decision(Rules.MISSED, "${inf.name} was missed",
                    when {
                        ctx.paused -> "Automation is paused, so the session was not moved."
                        e.manual -> "You scheduled this session by hand, so automation leaves it alone."
                        else -> "Only strength sessions are moved automatically."
                    },
                    mapOf("date" to day.toString()), listOf(markMissed), Direction.NEUTRAL)
                continue
            }
            if (budget <= 0) {
                out += Decision(Rules.MISSED, "${inf.name} was missed",
                    "The limit of ${config.maxAutoReschedulesPerWeek} automatic reschedules this week was reached. Move it by hand if you want.",
                    mapOf("date" to day.toString(), "reschedulesThisWeek" to "${config.maxAutoReschedulesPerWeek}"),
                    listOf(markMissed), Direction.NEUTRAL)
                continue
            }
            val candidates = days(day.plusDays(1), Weeks.weekEnd(day))
            val decision = place(e, inf, candidates, ctx.copy(entries = entries), Rules.MISSED,
                "Not started on $day", onFail = { listOf(markMissed) })
            out += decision
            entries = applyLocal(entries, decision.changes)
            budget--
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // Unavailable day (triggered by the user, so it does not consume the weekly budget)
    // ------------------------------------------------------------------------------------------

    fun onDayUnavailable(day: LocalDate, ctx: Context): List<Decision> {
        val avail = Availability(ctx.availability.windows, ctx.availability.unavailable + day)
        var entries = ctx.entries
        val out = mutableListOf<Decision>()
        val affected = entries.filter { it.date == day && it.status == EntryStatus.PLANNED }
        for (e in affected) {
            val inf = info(e) ?: continue
            if (e.manual) {
                out += Decision(Rules.UNAVAILABLE, "${inf.name} kept on $day",
                    "You scheduled this session by hand; move it yourself if needed.", mapOf("date" to "$day"), emptyList(), Direction.NEUTRAL)
                continue
            }
            val from = maxOf(Weeks.weekStart(day), ctx.now.toLocalDate())
            val candidates = days(from, Weeks.weekEnd(day)).filter { it != day }
            val d = place(e, inf, candidates, Context(entries, avail, ctx.now, 0, false), Rules.UNAVAILABLE,
                "$day marked as unavailable",
                onFail = { listOf(Change.SetStatus(e.id, EntryStatus.PLANNED, EntryStatus.SKIPPED)) })
            out += d.copy(countsAsReschedule = false)
            entries = applyLocal(entries, d.changes)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // Sleep: short version after N short nights in a row
    // ------------------------------------------------------------------------------------------

    /** [sleepHours] maps a wake-up date to hours slept that night. */
    fun onSleepLogged(sleepHours: Map<LocalDate, Double>, today: LocalDate, ctx: Context): Decision? {
        if (ctx.paused) return null
        val n = config.shortSleepNightsForShortSession
        val nights = (0 until n).map { today.minusDays(it.toLong()) }
        if (nights.any { (sleepHours[it] ?: return null) >= config.shortSleepHours }) return null
        val next = ctx.entries
            .filter { it.status == EntryStatus.PLANNED && !it.started && !it.manual && it.start >= ctx.now && info(it)?.isStrength == true }
            .minByOrNull { it.start } ?: return null
        if (next.variant == Variant.SHORT) return null
        return Decision(Rules.SLEEP_SHORT, "Next session switched to the short version",
            "You slept under ${fmt(config.shortSleepHours)} h for $n nights in a row.",
            nights.sorted().associate { "sleep $it" to "${fmt(sleepHours.getValue(it))} h" } + ("session" to "${name(next.sessionId)} ${next.start}"),
            listOf(Change.SetVariant(next.id, Variant.FULL, Variant.SHORT)), Direction.REDUCTION)
    }

    // ------------------------------------------------------------------------------------------
    // Pain: replace the next session for the same muscles by recovery work
    // ------------------------------------------------------------------------------------------

    fun onPain(exerciseName: String, muscles: Set<String>, pain: Int, currentEntryId: Long?, ctx: Context): Decision? {
        if (ctx.paused || pain < config.painReplaceThreshold) return null
        val next = ctx.entries
            .filter {
                it.id != currentEntryId && it.status == EntryStatus.PLANNED && !it.started && !it.manual &&
                    it.start > ctx.now && (info(it)?.groups.orEmpty() intersect muscles).isNotEmpty()
            }
            .minByOrNull { it.start } ?: return null
        val change = if (recoverySession != null) Change.ReplaceSession(next.id, next.sessionId, recoverySession)
        else Change.SetStatus(next.id, EntryStatus.PLANNED, EntryStatus.SKIPPED)
        return Decision(Rules.PAIN_RECOVERY,
            if (recoverySession != null) "Next ${name(next.sessionId)} replaced by ${name(recoverySession)}" else "Next ${name(next.sessionId)} skipped",
            "You reported pain $pain/10 during $exerciseName. The next session for the same muscles becomes recovery work.",
            mapOf("exercise" to exerciseName, "pain" to "$pain", "muscles" to muscles.sorted().joinToString(), "session" to "${name(next.sessionId)} ${next.start}"),
            listOf(change), Direction.REDUCTION)
    }

    // ------------------------------------------------------------------------------------------
    // Placement
    // ------------------------------------------------------------------------------------------

    private fun place(
        e: CalendarEntry, inf: SessionInfo, candidates: List<LocalDate>, ctx: Context, rule: String, why: String,
        onFail: () -> List<Change>,
    ): Decision {
        for (variant in listOf(e.variant, Variant.SHORT).distinct()) {
            val slot = findSlot(e, inf, variant, candidates, ctx) ?: continue
            val changes = buildList {
                add(Change.MoveEntry(e.id, e.start.toString(), slot.toString()))
                if (variant != e.variant) add(Change.SetVariant(e.id, e.variant, variant))
            }
            val shortNote = if (variant != e.variant) " Only the short version fits." else ""
            return Decision(rule, "${inf.name} moved to ${slot.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} ${slot.toLocalDate()} ${slot.toLocalTime()}",
                "$why. Moved to the next valid time slot.$shortNote",
                mapOf("from" to e.start.toString(), "to" to slot.toString(), "variant" to variant.name),
                changes, if (variant != e.variant) Direction.REDUCTION else Direction.NEUTRAL, countsAsReschedule = true)
        }
        return Decision(rule, "${inf.name} skipped this week",
            "$why. No valid slot is left this week (rest between muscle groups, weekly limit and your time windows), so it was skipped.",
            mapOf("from" to e.start.toString()), onFail(), Direction.REDUCTION, countsAsReschedule = true)
    }

    fun findSlot(e: CalendarEntry, inf: SessionInfo, variant: Variant, candidates: List<LocalDate>, ctx: Context): LocalDateTime? {
        val minutes = inf.minutes(variant).toLong()
        for (day in candidates) {
            for (w in ctx.availability.windowsOn(day)) {
                var start = LocalDateTime.of(day, w.start)
                if (start < ctx.now) start = roundUp(ctx.now)
                if (start.plusMinutes(minutes) > LocalDateTime.of(day, w.end)) continue
                if (fits(e, inf, start, ctx.entries)) return start
            }
        }
        return null
    }

    fun fits(e: CalendarEntry, inf: SessionInfo, start: LocalDateTime, entries: List<CalendarEntry>): Boolean {
        val others = entries.filter { it.id != e.id && (it.status == EntryStatus.PLANNED || it.status == EntryStatus.DONE) }
        for (o in others) {
            val oi = info(o) ?: continue
            val hours = Duration.between(start, o.start).toMinutes() / 60.0 // positive: other is after
            if (inf.isStrength && oi.isStrength) {
                if ((inf.groups intersect oi.groups).isNotEmpty() && kotlin.math.abs(hours) < config.minHoursBetweenSameGroup) return false
            }
            if (inf.isLegs && oi.type == SessionType.INTERVAL && hours < 0 && -hours < config.noIntervalHoursBeforeLegs) return false
            if (inf.type == SessionType.INTERVAL && oi.isLegs && hours > 0 && hours < config.noIntervalHoursBeforeLegs) return false
        }
        if (inf.isStrength) {
            val strength = others.filter { info(it)?.isStrength == true }
            if (strength.count { it.date == start.toLocalDate() } >= config.maxStrengthPerDay) return false
            if (strength.count { Weeks.sameWeek(it.date, start.toLocalDate()) } >= config.maxStrengthPerWeek) return false
        }
        return true
    }

    private fun days(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()

    private fun roundUp(t: LocalDateTime): LocalDateTime {
        val base = t.truncatedTo(ChronoUnit.HOURS)
        val m = ((t.minute + 14) / 15) * 15
        return base.plusMinutes(m.toLong())
    }

    companion object {
        /** Applies calendar changes to an in-memory entry list (used to chain several decisions). */
        fun applyLocal(entries: List<CalendarEntry>, changes: List<Change>): List<CalendarEntry> = entries.map { e ->
            changes.fold(e) { acc, c ->
                when {
                    c is Change.MoveEntry && c.entryId == acc.id -> acc.copy(start = LocalDateTime.parse(c.to))
                    c is Change.SetVariant && c.entryId == acc.id -> acc.copy(variant = c.to)
                    c is Change.ReplaceSession && c.entryId == acc.id -> acc.copy(sessionId = c.to)
                    c is Change.SetStatus && c.entryId == acc.id -> acc.copy(status = c.to)
                    else -> acc
                }
            }
        }

        fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", d)
    }
}
