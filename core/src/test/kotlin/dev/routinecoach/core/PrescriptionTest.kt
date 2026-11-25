package dev.routinecoach.core

import dev.routinecoach.core.engine.DateRange
import dev.routinecoach.core.engine.ExerciseState
import dev.routinecoach.core.engine.PlanTimeline
import dev.routinecoach.core.engine.Prescriber
import dev.routinecoach.core.engine.Variant
import dev.routinecoach.core.pack.DirectorySource
import dev.routinecoach.core.pack.PackLoadResult
import dev.routinecoach.core.pack.PackLoader
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrescriptionTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "content/library").isDirectory }
    private val pack = (PackLoader().load(DirectorySource(File(root, "content/demo-pack")), DirectorySource(File(root, "content/library"))) as PackLoadResult.Success).pack
    private val start: LocalDate = LocalDate.of(2026, 11, 2) // a Monday

    @Test
    fun `phase follows the week of the plan`() {
        assertEquals("ramp", PlanTimeline.phase(pack.plan, start, start).id)
        assertEquals("ramp", PlanTimeline.phase(pack.plan, start, start.plusDays(13)).id)
        assertEquals("build", PlanTimeline.phase(pack.plan, start, start.plusDays(14)).id)
        assertEquals("main", PlanTimeline.phase(pack.plan, start, start.plusDays(28)).id)
        assertEquals("main", PlanTimeline.phase(pack.plan, start, start.plusDays(400)).id)
    }

    @Test
    fun `phase sets and effort are shown in the prescription`() {
        val p = Prescriber.prescribe(pack, "full_a", Variant.FULL, start, start, emptyMap())
        val push = p.blocks.first().items.first()
        assertEquals(2, push.sets)
        assertEquals(3, push.rirMin)
        assertEquals(3, push.cadence!!.eccentricSec) // ramp phase slows the eccentric
    }

    @Test
    fun `programmed deload halves the sets`() {
        // everyWeeks = 6 -> week index 5 is a deload week.
        val deloadDay = start.plusWeeks(5)
        assertTrue(PlanTimeline.isProgrammedDeload(pack.plan, start, deloadDay))
        assertFalse(PlanTimeline.isProgrammedDeload(pack.plan, start, start.plusWeeks(4)))
        val p = Prescriber.prescribe(pack, "full_a", Variant.FULL, deloadDay, start, emptyMap())
        assertTrue(p.deload)
        assertEquals(2, p.blocks.first().items.first().sets) // ceil(3 / 2)
    }

    @Test
    fun `automatic deload ranges also halve the sets`() {
        val day = start.plusWeeks(4)
        val p = Prescriber.prescribe(pack, "full_a", Variant.FULL, day, start, emptyMap(), listOf(DateRange(day, day.plusDays(6))))
        assertEquals(2, p.blocks.first().items.first().sets)
    }

    @Test
    fun `short version keeps only the short blocks`() {
        val full = Prescriber.prescribe(pack, "full_a", Variant.FULL, start, start, emptyMap())
        val short = Prescriber.prescribe(pack, "full_a", Variant.SHORT, start, start, emptyMap())
        assertEquals(listOf("A", "B", "C"), full.blocks.map { it.id })
        assertEquals(listOf("A", "B"), short.blocks.map { it.id })
        assertEquals(1, short.stretches!!.rounds)
    }

    @Test
    fun `extra sets are capped and swapped exercises are used`() {
        val day = start.plusWeeks(4)
        val states = mapOf(
            "push_up" to ExerciseState("push_up", extraSets = 3, swappedTo = "floor_press"),
            "floor_press" to ExerciseState("floor_press", level = 1),
        )
        val item = Prescriber.prescribe(pack, "full_a", Variant.FULL, day, start, states).blocks.first().items.first()
        assertEquals("floor_press", item.exerciseId)
        assertEquals("push_up", item.plannedExerciseId)
        assertEquals(4, item.sets) // 3 + 3 capped at 4
        assertEquals(1, item.level)
    }
}
