package dev.routinecoach.core

import dev.routinecoach.core.engine.Availability
import dev.routinecoach.core.engine.CalendarEntry
import dev.routinecoach.core.engine.Change
import dev.routinecoach.core.engine.EntryStatus
import dev.routinecoach.core.engine.Rescheduler
import dev.routinecoach.core.engine.SessionInfo
import dev.routinecoach.core.engine.TimeWindow
import dev.routinecoach.core.engine.Variant
import dev.routinecoach.core.pack.RuleConfig
import dev.routinecoach.core.pack.SessionType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReschedulerTest {
    private val config = RuleConfig()
    private val sessions = listOf(
        SessionInfo("upper", "Upper", SessionType.STRENGTH, setOf("chest", "back", "shoulders"), 30, 20),
        SessionInfo("lower", "Lower", SessionType.STRENGTH, setOf("quads", "glutes", "hamstrings"), 30, 20),
        SessionInfo("intervals", "Intervals", SessionType.INTERVAL, emptySet(), 25, 20),
        SessionInfo("walk", "Walk", SessionType.CARDIO, emptySet(), 30, 20),
    ).associateBy { it.id }
    private val engine = Rescheduler(config, sessions, recoverySession = "walk")

    // Week of Monday 2026-11-23.
    private val mon: LocalDate = LocalDate.of(2026, 11, 23)
    private fun day(i: Long) = mon.plusDays(i)
    private fun at(i: Long, h: Int = 18) = LocalDateTime.of(day(i), LocalTime.of(h, 0))

    private val evenings = Availability(DayOfWeek.entries.associateWith { listOf(TimeWindow.parse("18:00-21:00")!!) })

    /** Mon upper, Tue lower, Wed walk, Thu upper, Fri lower, Sat walk. */
    private fun week(): List<CalendarEntry> = listOf(
        CalendarEntry(1, "upper", at(0)), CalendarEntry(2, "lower", at(1)), CalendarEntry(3, "walk", at(2)),
        CalendarEntry(4, "upper", at(3)), CalendarEntry(5, "lower", at(4)), CalendarEntry(6, "walk", at(5)),
    )

    private fun ctx(entries: List<CalendarEntry> = week(), now: LocalDateTime = at(1, 0), used: Int = 0, paused: Boolean = false, avail: Availability = evenings) =
        Rescheduler.Context(entries, avail, now, used, paused)

    // ---------------------------------------------------------------- missed sessions

    @Test
    fun `missed session moves to the next valid slot respecting 48 h and one strength per day`() {
        val d = engine.onDayEnded(day(0), ctx()).single()
        // Tue/Fri already have strength, Wed is within 48 h of Thu upper, Sat 18:00 is exactly 48 h after Thu.
        assertEquals(listOf<Change>(Change.MoveEntry(1, at(0).toString(), at(5).toString())), d.changes)
        assertTrue(d.countsAsReschedule)
    }

    @Test
    fun `missed session is not moved when the weekly limit is reached`() {
        val d = engine.onDayEnded(day(0), ctx(used = 2)).single()
        assertEquals(listOf<Change>(Change.SetStatus(1, EntryStatus.PLANNED, EntryStatus.MISSED)), d.changes)
        assertTrue(d.reason.contains("limit"))
    }

    @Test
    fun `manual entries are never moved by automation`() {
        val entries = week().map { if (it.id == 1L) it.copy(manual = true) else it }
        val d = engine.onDayEnded(day(0), ctx(entries)).single()
        assertEquals(listOf<Change>(Change.SetStatus(1, EntryStatus.PLANNED, EntryStatus.MISSED)), d.changes)
    }

    @Test
    fun `paused automation only marks the session as missed`() {
        val d = engine.onDayEnded(day(0), ctx(paused = true)).single()
        assertIs<Change.SetStatus>(d.changes.single())
    }

    @Test
    fun `started or done sessions are not treated as missed`() {
        val entries = week().map { if (it.id == 1L) it.copy(started = true) else it }
        assertTrue(engine.onDayEnded(day(0), ctx(entries)).isEmpty())
    }

    @Test
    fun `session is skipped with a warning when no slot is left this week`() {
        val weekdaysOnly = Availability((1..5).associate { DayOfWeek.of(it) to listOf(TimeWindow.parse("18:00-21:00")!!) })
        // Thursday upper missed: Fri has lower, Sat/Sun have no windows.
        val d = engine.onDayEnded(day(3), ctx(now = at(4, 0), avail = weekdaysOnly)).single()
        assertEquals(listOf<Change>(Change.SetStatus(4, EntryStatus.PLANNED, EntryStatus.MISSED)), d.changes)
        assertTrue(d.reason.contains("No valid slot"))
    }

    @Test
    fun `short version is used when only a short window fits`() {
        val avail = Availability(
            DayOfWeek.entries.associateWith { listOf(TimeWindow.parse("18:00-21:00")!!) } +
                (DayOfWeek.SATURDAY to listOf(TimeWindow.parse("19:00-19:25")!!)) + (DayOfWeek.SUNDAY to emptyList()),
        )
        val d = engine.onDayEnded(day(0), ctx(avail = avail)).single()
        assertEquals(
            listOf(Change.MoveEntry(1, at(0).toString(), at(5, 19).toString()), Change.SetVariant(1, Variant.FULL, Variant.SHORT)),
            d.changes,
        )
    }

    @Test
    fun `weekly strength limit blocks a fifth session`() {
        val extra = CalendarEntry(99, "upper", at(5)) // 48 h after Thursday, alone on Saturday
        assertTrue(!engine.fits(extra, sessions.getValue("upper"), at(5), week())) // already 4 strength sessions
        assertTrue(engine.fits(extra, sessions.getValue("upper"), at(5), week().filter { it.id != 2L }))
    }

    @Test
    fun `48 hours is the boundary for the same muscle group`() {
        val entries = listOf(CalendarEntry(1, "upper", at(0)))
        val e = CalendarEntry(2, "upper", at(2))
        assertTrue(engine.fits(e, sessions.getValue("upper"), at(2), entries)) // exactly 48 h
        assertTrue(!engine.fits(e, sessions.getValue("upper"), at(2, 17), entries)) // 47 h
    }

    @Test
    fun `no interval session in the 48 hours before legs`() {
        val entries = listOf(CalendarEntry(1, "lower", at(3)))
        val i = CalendarEntry(2, "intervals", at(2))
        assertTrue(!engine.fits(i, sessions.getValue("intervals"), at(2), entries)) // 24 h before legs
        assertTrue(engine.fits(i, sessions.getValue("intervals"), at(1), entries)) // 48 h before legs
        assertTrue(engine.fits(i, sessions.getValue("intervals"), at(4), entries)) // after legs is fine
        // And the symmetric check when placing the leg session.
        val legs = CalendarEntry(3, "lower", at(5))
        assertTrue(!engine.fits(legs, sessions.getValue("lower"), at(5), listOf(CalendarEntry(4, "intervals", at(4)))))
    }

    // ---------------------------------------------------------------- unavailable day

    @Test
    fun `unavailable day redistributes its sessions within the week`() {
        val d = engine.onDayUnavailable(day(1), ctx(now = at(0, 8)))
        val move = d.single().changes.single() as Change.MoveEntry
        assertEquals(2L, move.entryId)
        val newDay = LocalDateTime.parse(move.to).toLocalDate()
        // Lower cannot be Mon (upper), Wed..Fri constrained by Fri lower (48 h) -> Wed is exactly 48 h before Fri.
        assertEquals(day(2), newDay)
        assertTrue(!d.single().countsAsReschedule)
    }

    // ---------------------------------------------------------------- sleep

    @Test
    fun `three short nights switch the next strength session to the short version`() {
        val today = day(1)
        val sleep = mapOf(today to 5.5, today.minusDays(1) to 5.0, today.minusDays(2) to 4.0)
        val d = engine.onSleepLogged(sleep, today, ctx(now = at(1, 8)))!!
        assertEquals(listOf<Change>(Change.SetVariant(2, Variant.FULL, Variant.SHORT)), d.changes)
    }

    @Test
    fun `two short nights or a missing night do nothing`() {
        val today = day(1)
        assertNull(engine.onSleepLogged(mapOf(today to 5.0, today.minusDays(1) to 5.0, today.minusDays(2) to 6.0), today, ctx(now = at(1, 8))))
        assertNull(engine.onSleepLogged(mapOf(today to 5.0, today.minusDays(1) to 5.0), today, ctx(now = at(1, 8))))
    }

    @Test
    fun `sleep rule ignores sessions already short`() {
        val today = day(1)
        val entries = week().map { if (it.id == 2L) it.copy(variant = Variant.SHORT) else it }
        val sleep = (0L..2L).associate { today.minusDays(it) to 5.0 }
        assertNull(engine.onSleepLogged(sleep, today, ctx(entries, now = at(1, 8))))
    }

    // ---------------------------------------------------------------- pain

    @Test
    fun `pain of 6 replaces the next session of the same muscles by recovery`() {
        val d = engine.onPain("Push-up", setOf("chest", "triceps"), 6, currentEntryId = 1, ctx(now = at(0, 18)))!!
        assertEquals(listOf<Change>(Change.ReplaceSession(4, "upper", "walk")), d.changes)
    }

    @Test
    fun `pain below the threshold or when paused does nothing`() {
        assertNull(engine.onPain("Push-up", setOf("chest"), 5, 1, ctx(now = at(0, 18))))
        assertNull(engine.onPain("Push-up", setOf("chest"), 8, 1, ctx(now = at(0, 18), paused = true)))
    }

    @Test
    fun `pain does not touch a manually scheduled session`() {
        val entries = week().map { if (it.id == 4L) it.copy(manual = true) else it }
        assertNull(engine.onPain("Push-up", setOf("chest"), 7, 1, ctx(entries, now = at(0, 18))))
    }
}
