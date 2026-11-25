package dev.routinecoach.core

import dev.routinecoach.core.engine.Change
import dev.routinecoach.core.engine.Direction
import dev.routinecoach.core.engine.ExercisePerformance
import dev.routinecoach.core.engine.ExerciseState
import dev.routinecoach.core.engine.ProgressionEngine
import dev.routinecoach.core.engine.Rules
import dev.routinecoach.core.engine.SessionPerformance
import dev.routinecoach.core.engine.SetRecord
import dev.routinecoach.core.pack.Exercise
import dev.routinecoach.core.pack.IllustrationRef
import dev.routinecoach.core.pack.Illustrations
import dev.routinecoach.core.pack.Phase
import dev.routinecoach.core.pack.RuleConfig
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun testExercise(id: String, muscle: String, ladder: List<String> = listOf("L0", "L1", "L2"), alternatives: List<String> = emptyList()) = Exercise(
    id = id, name = id.replaceFirstChar { it.uppercase() }, primaryMuscle = muscle, steps = listOf("Do it."),
    ladder = ladder, alternatives = alternatives,
    illustrations = Illustrations(IllustrationRef("$id-start.svg", "start"), IllustrationRef("$id-end.svg", "end")),
)

class ProgressionTest {
    private val config = RuleConfig()
    private val engine = ProgressionEngine(config)
    private val today: LocalDate = LocalDate.of(2026, 11, 24)
    private val main = Phase("main", "Main", null, sets = 3, rirMin = 1, rirMax = 2)
    private val exercises = listOf(
        testExercise("push_up", "chest", alternatives = listOf("floor_press")),
        testExercise("floor_press", "chest"),
        testExercise("row", "back"),
    ).associateBy { it.id }

    private fun perf(id: String, daysAgo: Long, reps: List<Int>, rir: Int? = 1, pain: Int = 0, level: Int = 0, deload: Boolean = false) =
        ExercisePerformance(id, today.minusDays(daysAgo), reps.map { SetRecord(it, level, rir, pain) }, 8, 20, 1, 2, deload)

    private fun session(daysAgo: Long, vararg ex: ExercisePerformance, template: String = "upper", deload: Boolean = false) =
        SessionPerformance(daysAgo, template, today.minusDays(daysAgo), deload, ex.toList())

    private fun ctx(
        sessions: List<SessionPerformance>,
        states: Map<String, ExerciseState> = emptyMap(),
        phase: Phase = main,
        deloadActive: Boolean = false,
        sleep: Map<LocalDate, Double> = emptyMap(),
        planned: Map<String, Int> = mapOf("chest" to 6, "back" to 6),
        targets: Map<String, Int> = mapOf("chest" to 8, "back" to 6),
        currentSets: Map<String, Pair<Int, Int>> = mapOf("push_up" to (3 to 4), "row" to (3 to 4)),
        lastDeloadEnd: LocalDate? = null,
        paused: Boolean = false,
    ) = ProgressionEngine.Context(today, phase, deloadActive, paused, exercises, states, sessions, sleep, planned, targets, currentSets, lastDeloadEnd)

    private val topTwice = listOf(
        session(0, perf("push_up", 0, listOf(20, 20, 20))),
        session(3, perf("push_up", 3, listOf(20, 21, 20))),
    )

    // ---------------------------------------------------------------- increase difficulty

    @Test
    fun `increase after two sessions at the top of the range with effort on target`() {
        val d = engine.increase("push_up", ctx(topTwice))!!
        assertEquals(listOf<Change>(Change.SetLevel("push_up", 0, 1)), d.changes)
        assertEquals(Direction.INCREASE, d.direction)
    }

    @Test
    fun `no increase when one set missed the top of the range`() {
        val s = listOf(session(0, perf("push_up", 0, listOf(20, 19, 20))), topTwice[1])
        assertNull(engine.increase("push_up", ctx(s)))
    }

    @Test
    fun `no increase when effort was beyond the target (too few reps in reserve)`() {
        val s = listOf(session(0, perf("push_up", 0, listOf(20, 20, 20), rir = 0)), topTwice[1])
        assertNull(engine.increase("push_up", ctx(s)))
    }

    @Test
    fun `at most one difficulty change per exercise per week`() {
        val states = mapOf("push_up" to ExerciseState("push_up", lastLevelChange = today.minusDays(6)))
        assertNull(engine.increase("push_up", ctx(topTwice, states)))
        val old = mapOf("push_up" to ExerciseState("push_up", lastLevelChange = today.minusDays(7)))
        assertTrue(engine.increase("push_up", ctx(topTwice, old)) != null)
    }

    @Test
    fun `no increase at the top of the ladder`() {
        val states = mapOf("push_up" to ExerciseState("push_up", level = 2))
        val s = listOf(session(0, perf("push_up", 0, listOf(20, 20, 20), level = 2)), session(3, perf("push_up", 3, listOf(20, 20, 20), level = 2)))
        assertNull(engine.increase("push_up", ctx(s, states)))
    }

    @Test
    fun `no automatic increase with pain of 4 or more in the last 7 days`() {
        val painful = topTwice + session(6, perf("row", 6, listOf(12, 12), pain = 4))
        assertTrue(engine.evaluate(listOf("push_up"), ctx(painful)).none { it.direction == Direction.INCREASE })
        val older = topTwice + session(7, perf("row", 7, listOf(12, 12), pain = 4))
        assertTrue(engine.evaluate(listOf("push_up"), ctx(older)).any { it.rule == Rules.INCREASE })
    }

    @Test
    fun `no increases during a phase without auto progression or when paused`() {
        val ramp = main.copy(id = "ramp", autoProgression = false)
        assertTrue(engine.evaluate(listOf("push_up"), ctx(topTwice, phase = ramp)).isEmpty())
        assertTrue(engine.evaluate(listOf("push_up"), ctx(topTwice, paused = true)).isEmpty())
    }

    // ---------------------------------------------------------------- add set

    private val easyTwice = listOf(
        session(0, perf("push_up", 0, listOf(15, 15, 15), rir = 4)),
        session(3, perf("push_up", 3, listOf(15, 15, 15), rir = 3)),
    )

    @Test
    fun `add a set when volume is below target and sessions were easy without pain`() {
        val d = engine.addSet("push_up", ctx(easyTwice), mutableSetOf())!!
        assertEquals(listOf<Change>(Change.SetExtraSets("push_up", 0, 1)), d.changes)
    }

    @Test
    fun `no extra set when the weekly target is met`() {
        assertNull(engine.addSet("push_up", ctx(easyTwice, planned = mapOf("chest" to 8)), mutableSetOf()))
    }

    @Test
    fun `no extra set above 4 sets per exercise`() {
        assertNull(engine.addSet("push_up", ctx(easyTwice, currentSets = mapOf("push_up" to (4 to 4))), mutableSetOf()))
    }

    @Test
    fun `at most one extra set per muscle every two weeks`() {
        val recent = mapOf("floor_press" to ExerciseState("floor_press", lastSetAdded = today.minusDays(13)))
        assertNull(engine.addSet("push_up", ctx(easyTwice, recent), mutableSetOf()))
        val old = mapOf("floor_press" to ExerciseState("floor_press", lastSetAdded = today.minusDays(14)))
        assertTrue(engine.addSet("push_up", ctx(easyTwice, old), mutableSetOf()) != null)
    }

    @Test
    fun `no extra set with any pain in the last 7 days`() {
        val s = listOf(session(0, perf("push_up", 0, listOf(15, 15, 15), rir = 4, pain = 1)), easyTwice[1])
        assertNull(engine.addSet("push_up", ctx(s), mutableSetOf()))
    }

    // ---------------------------------------------------------------- deload

    @Test
    fun `deload after performance drops in two sessions in a row`() {
        val s = listOf(
            session(0, perf("push_up", 0, listOf(12, 12, 12))),
            session(3, perf("push_up", 3, listOf(14, 14, 14))),
            session(7, perf("push_up", 7, listOf(16, 16, 16))),
        )
        val d = engine.deload(ctx(s))!!
        assertEquals(listOf<Change>(Change.Deload(today.toString(), today.plusDays(6).toString())), d.changes)
        assertEquals(Direction.REDUCTION, d.direction)
    }

    @Test
    fun `a single drop does not trigger a deload`() {
        val s = listOf(
            session(0, perf("push_up", 0, listOf(12, 12, 12))),
            session(3, perf("push_up", 3, listOf(16, 16, 16))),
            session(7, perf("push_up", 7, listOf(16, 16, 16))),
        )
        assertNull(engine.deload(ctx(s)))
    }

    @Test
    fun `deload after 5 short nights in 7 days`() {
        val sleep = (0L..4L).associate { today.minusDays(it) to 5.5 } + (today.minusDays(5) to 8.0)
        assertTrue(engine.deload(ctx(emptyList(), sleep = sleep)) != null)
        val four = (0L..3L).associate { today.minusDays(it) to 5.5 }
        assertNull(engine.deload(ctx(emptyList(), sleep = four)))
    }

    @Test
    fun `deload after pain in three sessions in a row`() {
        val s = (0L..2L).map { session(it * 2, perf("row", it * 2, listOf(12, 12), pain = 4)) }
        assertTrue(engine.deload(ctx(s)) != null)
        val s2 = s.take(2) + session(4, perf("row", 4, listOf(12, 12), pain = 3))
        assertNull(engine.deload(ctx(s2)))
    }

    @Test
    fun `no new deload shortly after the previous one or while one is active`() {
        val sleep = (0L..6L).associate { today.minusDays(it) to 5.0 }
        assertNull(engine.deload(ctx(emptyList(), sleep = sleep, lastDeloadEnd = today.minusDays(10))))
        assertNull(engine.deload(ctx(emptyList(), sleep = sleep, deloadActive = true)))
    }

    @Test
    fun `deload suppresses increases in the same evaluation`() {
        val sleep = (0L..6L).associate { today.minusDays(it) to 5.0 }
        val out = engine.evaluate(listOf("push_up"), ctx(topTwice, sleep = sleep))
        assertEquals(listOf(Rules.DELOAD), out.map { it.rule })
    }

    // ---------------------------------------------------------------- swap

    @Test
    fun `recurring joint pain swaps to the pack alternative`() {
        val s = (0L..2L).map { session(it * 2, perf("push_up", it * 2, listOf(12, 12), pain = 5)) }
        val d = engine.swap("push_up", ctx(s))!!
        assertEquals(listOf<Change>(Change.SwapExercise("push_up", null, "floor_press")), d.changes)
    }

    @Test
    fun `two painful sessions are not enough to swap`() {
        val s = (0L..1L).map { session(it * 2, perf("push_up", it * 2, listOf(12, 12), pain = 5)) } +
            session(4, perf("push_up", 4, listOf(12, 12), pain = 2))
        assertNull(engine.swap("push_up", ctx(s)))
    }

    @Test
    fun `without an alternative the swap rule only warns`() {
        val s = (0L..2L).map { session(it * 2, perf("row", it * 2, listOf(12, 12), pain = 6)) }
        val d = engine.swap("row", ctx(s))!!
        assertTrue(d.isNoticeOnly)
    }
}
