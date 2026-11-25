package dev.routinecoach.core.engine

import dev.routinecoach.core.pack.Exercise
import java.time.LocalDate

object Metrics {
    /** Completed working sets per primary muscle. Each set counts once, for its exercise's primary muscle. */
    fun setsByMuscle(exerciseIdsOfSets: List<String>, exercises: Map<String, Exercise>): Map<String, Int> =
        exerciseIdsOfSets.mapNotNull { exercises[it]?.primaryMuscle }.groupingBy { it }.eachCount()

    data class MuscleVolume(val muscle: String, val done: Int, val target: Int?)

    fun weeklyVolume(done: Map<String, Int>, targets: Map<String, Int>): List<MuscleVolume> =
        (done.keys + targets.keys).distinct().sorted().map { MuscleVolume(it, done[it] ?: 0, targets[it]) }

    /**
     * Trailing moving average over [days] calendar days. Several entries on one day are averaged first, so a
     * single unusual reading only moves the curve by a fraction.
     */
    fun movingAverage(points: List<Pair<LocalDate, Double>>, days: Int = 7): List<Pair<LocalDate, Double>> {
        val daily = points.groupBy({ it.first }, { it.second }).mapValues { it.value.average() }.toSortedMap()
        return daily.keys.map { d ->
            val window = daily.subMap(d.minusDays(days - 1L), d.plusDays(1))
            d to window.values.average()
        }
    }
}
