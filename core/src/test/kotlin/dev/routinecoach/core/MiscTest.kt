package dev.routinecoach.core

import dev.routinecoach.core.engine.Change
import dev.routinecoach.core.engine.ChangeCodec
import dev.routinecoach.core.engine.Metrics
import dev.routinecoach.core.engine.Variant
import dev.routinecoach.core.illustration.PoseRenderer
import dev.routinecoach.core.pack.Pose
import dev.routinecoach.core.pack.PoseSet
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiscTest {
    @Test
    fun `moving average dampens a single outlier`() {
        val d0 = LocalDate.of(2026, 11, 1)
        val pts = (0L..6L).map { d0.plusDays(it) to 63.0 } + (d0.plusDays(7) to 66.5)
        val ma = Metrics.movingAverage(pts)
        assertEquals(63.0, ma[6].second, 1e-9)
        assertEquals(63.5, ma[7].second, 1e-9) // (6 * 63 + 66.5) / 7
    }

    @Test
    fun `several readings on one day count as their mean`() {
        val d = LocalDate.of(2026, 11, 1)
        val ma = Metrics.movingAverage(listOf(d to 60.0, d to 62.0))
        assertEquals(listOf(d to 61.0), ma)
    }

    @Test
    fun `weekly volume counts sets per primary muscle`() {
        val ex = listOf(testExercise("push_up", "chest"), testExercise("row", "back")).associateBy { it.id }
        val v = Metrics.weeklyVolume(Metrics.setsByMuscle(listOf("push_up", "push_up", "row"), ex), mapOf("chest" to 6, "quads" to 6))
        assertEquals(listOf(Metrics.MuscleVolume("back", 1, null), Metrics.MuscleVolume("chest", 2, 6), Metrics.MuscleVolume("quads", 0, 6)), v)
    }

    @Test
    fun `changes round trip as JSON and invert`() {
        val changes = listOf(
            Change.MoveEntry(1, "2026-11-23T18:00", "2026-11-28T18:00"), Change.SetVariant(1, Variant.FULL, Variant.SHORT),
            Change.SetLevel("push_up", 1, 2), Change.Deload("2026-11-24", "2026-11-30"), Change.SwapExercise("a", null, "b"),
        )
        assertEquals(changes, ChangeCodec.decode(ChangeCodec.encode(changes)))
        assertEquals(changes, changes.map { it.inverse().inverse() })
        assertEquals(Change.SetLevel("push_up", 2, 1), changes[2].inverse())
    }

    @Test
    fun `renderer produces small accessible SVG`() {
        val (s, e) = PoseRenderer.renderPair(PoseSet(Pose(), Pose(nearArm = listOf(90.0, 90.0)), listOf("nearHand")), "Standing", "Arms <forward>")
        assertTrue(s.startsWith("<svg") && s.contains("<title>Standing</title>"))
        assertTrue(e.contains("Arms &lt;forward&gt;"))
        assertTrue(s.length < 20 * 1024 && e.length < 20 * 1024)
        assertTrue(s.contains("<polygon"))
        assertTrue(!s.contains("marker"))
    }

    @Test
    fun `grounded figure touches the floor`() {
        val sk = PoseRenderer.place(Pose())
        assertEquals(PoseRenderer.FLOOR_Y - 3.0, maxOf(sk["nearToe"].y, sk["nearAnkle"].y), 1e-6)
    }
}
