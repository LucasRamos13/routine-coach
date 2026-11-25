package dev.routinecoach.core.engine

import dev.routinecoach.core.pack.ContentPack
import dev.routinecoach.core.pack.SessionType
import dev.routinecoach.core.pack.WEEKDAY_KEYS
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class Variant { FULL, SHORT }

enum class EntryStatus { PLANNED, DONE, SKIPPED, MISSED }

/** One scheduled session in the calendar. */
data class CalendarEntry(
    val id: Long,
    val sessionId: String,
    val start: LocalDateTime,
    val variant: Variant = Variant.FULL,
    val status: EntryStatus = EntryStatus.PLANNED,
    /** Set when the user changed this entry by hand; automation never modifies it afterwards. */
    val manual: Boolean = false,
    val started: Boolean = false,
) {
    val date: LocalDate get() = start.toLocalDate()
}

data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    val minutes: Long get() = ChronoUnit.MINUTES.between(start, end)
    override fun toString() = "$start-$end"

    companion object {
        fun parse(s: String): TimeWindow? = try {
            val (a, b) = s.split("-").map { LocalTime.parse(it.trim()) }
            if (a < b) TimeWindow(a, b) else null
        } catch (_: Exception) {
            null
        }
    }
}

class Availability(
    val windows: Map<DayOfWeek, List<TimeWindow>>,
    val unavailable: Set<LocalDate> = emptySet(),
) {
    fun windowsOn(date: LocalDate): List<TimeWindow> =
        if (date in unavailable) emptyList() else windows[date.dayOfWeek].orEmpty()

    companion object {
        fun fromKeys(map: Map<String, List<String>>, unavailable: Set<LocalDate> = emptySet()) = Availability(
            map.mapNotNull { (k, v) ->
                val idx = WEEKDAY_KEYS.indexOf(k)
                if (idx < 0) null else DayOfWeek.of(idx + 1) to v.mapNotNull(TimeWindow::parse)
            }.toMap(),
            unavailable,
        )
    }
}

/** Facts about a session template that the calendar rules need. */
data class SessionInfo(
    val id: String,
    val name: String,
    val type: SessionType,
    /** Primary muscles trained (empty for non-strength sessions). */
    val groups: Set<String>,
    val fullMinutes: Int,
    val shortMinutes: Int,
) {
    val isStrength get() = type == SessionType.STRENGTH
    val isLegs get() = groups.any { it in LEG_MUSCLES }

    fun minutes(v: Variant) = if (v == Variant.FULL) fullMinutes else shortMinutes

    companion object {
        val LEG_MUSCLES = setOf("quads", "glutes", "hamstrings", "calves", "adductors")

        fun index(pack: ContentPack): Map<String, SessionInfo> = pack.plan.sessions.associate { s ->
            val groups = if (s.type == SessionType.STRENGTH) {
                s.blocks.flatMap { b -> b.items.mapNotNull { pack.exercises[it.exercise]?.primaryMuscle } }.toSet()
            } else emptySet()
            s.id to SessionInfo(s.id, s.name, s.type, groups, s.durationMin, s.shortDurationMin)
        }
    }
}

object Weeks {
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun weekEnd(date: LocalDate): LocalDate = weekStart(date).plusDays(6)
    fun sameWeek(a: LocalDate, b: LocalDate) = weekStart(a) == weekStart(b)
}

/** Builds the planned entries of a week from the plan's weekly template. */
object ScheduleGenerator {
    val DEFAULT_TIME: LocalTime = LocalTime.of(18, 0)

    data class Planned(val sessionId: String, val start: LocalDateTime)

    fun week(weekTemplate: Map<String, List<String>>, weekStart: LocalDate, availability: Availability, sessions: Map<String, SessionInfo>): List<Planned> {
        val out = mutableListOf<Planned>()
        for ((i, key) in WEEKDAY_KEYS.withIndex()) {
            val date = weekStart.plusDays(i.toLong())
            var t = LocalDateTime.of(date, availability.windows[date.dayOfWeek]?.firstOrNull()?.start ?: DEFAULT_TIME)
            for (sid in weekTemplate[key].orEmpty()) {
                out += Planned(sid, t)
                t = t.plusMinutes((sessions[sid]?.fullMinutes ?: 30).toLong())
            }
        }
        return out
    }
}
