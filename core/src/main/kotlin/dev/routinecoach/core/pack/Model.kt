package dev.routinecoach.core.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Current version of the content pack schema. Bump it and add a [PackMigration] when the format changes. */
const val PACK_SCHEMA_VERSION = 1

@Serializable
data class PackManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    /** BCP-47 language of the pack texts. The foundation UI is always English; pack texts are data. */
    val language: String = "en",
    val description: String = "",
    val author: String = "",
    /**
     * When false, library exercises are only available as bases for `extends` (e.g. a translated pack that
     * should not also list the English originals).
     */
    val includeLibrary: Boolean = true,
)

// ---------------------------------------------------------------------------------------------
// Exercises
// ---------------------------------------------------------------------------------------------

@Serializable
enum class ExerciseMode {
    /** Counted repetitions. */
    @SerialName("reps") REPS,

    /** Timed effort (e.g. plank), logged in seconds. */
    @SerialName("time") TIME,

    /** Stretch held for a duration, usually per side. */
    @SerialName("hold") HOLD,
}

@Serializable
enum class ExerciseCategory {
    @SerialName("strength") STRENGTH,
    @SerialName("mobility") MOBILITY,
    @SerialName("warmup") WARMUP,
    @SerialName("cardio") CARDIO,
}

@Serializable
data class Cadence(
    val eccentricSec: Int = 2,
    val pauseBottomSec: Int = 0,
    val concentricSec: Int = 1,
    val pauseTopSec: Int = 0,
) {
    val repSeconds: Int get() = eccentricSec + pauseBottomSec + concentricSec + pauseTopSec
    override fun toString() = "$eccentricSec-$pauseBottomSec-$concentricSec-$pauseTopSec"
}

@Serializable
data class IllustrationRef(
    /** File name inside the pack's (or library's) `illustrations/` folder. */
    val file: String,
    /** Alternative text read by screen readers. */
    val alt: String,
)

@Serializable
data class Illustrations(
    val start: IllustrationRef,
    val end: IllustrationRef,
    /** Set to "approved" once a person reviewed the posture. Pending illustrations are flagged by the validator. */
    val review: String = "pending",
)

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val primaryMuscle: String,
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    val category: ExerciseCategory = ExerciseCategory.STRENGTH,
    val mode: ExerciseMode = ExerciseMode.REPS,
    /** Performed one side at a time; sets are logged per side. */
    val unilateral: Boolean = false,
    val summary: String = "",
    val steps: List<String>,
    val commonMistakes: List<String> = emptyList(),
    val breathing: String = "",
    val cadence: Cadence? = null,
    /**
     * Progression ladder, easiest first. Each rung can be a variation, a band strength or a slower cadence.
     * The logged "level" is an index into this list.
     */
    val ladder: List<String> = emptyList(),
    val easier: String = "",
    val harder: String = "",
    val contraindications: List<String> = emptyList(),
    /** Exercise ids that can replace this one (skip/substitute and pain swaps). */
    val alternatives: List<String> = emptyList(),
    val illustrations: Illustrations,
    /** Source poses used to generate the illustrations (optional; see docs/illustrations.md). */
    val poses: PoseSet? = null,
) {
    val muscles: Set<String> get() = (listOf(primaryMuscle) + secondaryMuscles).toSet()
    fun levelLabel(level: Int): String = ladder.getOrNull(level) ?: if (ladder.isEmpty()) "Standard" else ladder.last()
}

// ---------------------------------------------------------------------------------------------
// Plan
// ---------------------------------------------------------------------------------------------

@Serializable
enum class SessionType {
    @SerialName("strength") STRENGTH,
    @SerialName("cardio") CARDIO,
    @SerialName("interval") INTERVAL,
    @SerialName("mobility") MOBILITY,
}

@Serializable
data class Phase(
    val id: String,
    val name: String,
    /** Length in weeks; null means open-ended (must be the last phase). */
    val weeks: Int? = null,
    val sets: Int,
    val rirMin: Int,
    val rirMax: Int,
    /** Overrides the exercise eccentric duration during this phase. */
    val eccentricSec: Int? = null,
    /** When false, the engine never increases difficulty or volume during this phase. */
    val autoProgression: Boolean = true,
    val notes: String = "",
)

@Serializable
data class DeloadConfig(
    /** Programmed deload: every Nth week of the plan is a deload week. Null disables programmed deloads. */
    val everyWeeks: Int? = null,
    /** Fraction of sets kept during a deload (0.5 = half the sets, rounded up). */
    val setFactor: Double = 0.5,
)

@Serializable
data class BlockItem(
    val exercise: String,
    /** Repetition range (reps mode) or seconds range (time/hold mode), as [min, max]. */
    val range: List<Int>,
    /** Fixed number of sets. When absent the current phase decides. */
    val sets: Int? = null,
    /** Upper bound for sets (phase sets, added sets and fixed sets are capped by it). */
    val maxSets: Int? = null,
    val restSec: Int? = null,
    val note: String = "",
) {
    val min: Int get() = range.first()
    val max: Int get() = range.last()
}

@Serializable
data class Block(
    val id: String,
    /** One item is a straight set; two or more items form a superset performed back to back. */
    val items: List<BlockItem>,
    /** Rest after the block round (after the last item of a superset). */
    val restSec: Int = 75,
)

@Serializable
data class StretchRoutine(
    val exercises: List<String>,
    val holdSec: Int = 30,
    val rounds: Int = 2,
)

@Serializable
data class SessionTemplate(
    val id: String,
    val name: String,
    val type: SessionType,
    val description: String = "",
    /** Approximate duration of the full version in minutes. */
    val durationMin: Int = 30,
    /** Approximate duration of the short version in minutes. */
    val shortDurationMin: Int = 20,
    val warmup: List<String> = emptyList(),
    val blocks: List<Block> = emptyList(),
    /** Block ids kept in the short version. Defaults to the first two blocks. */
    val shortBlocks: List<String>? = null,
    /** Stretches after the session (strength) or the whole content (mobility). */
    val stretches: StretchRoutine? = null,
)

@Serializable
data class RuleConfig(
    // Calendar
    val minHoursBetweenSameGroup: Int = 48,
    val maxStrengthPerWeek: Int = 4,
    val maxStrengthPerDay: Int = 1,
    val noIntervalHoursBeforeLegs: Int = 48,
    val shortSleepHours: Double = 6.0,
    val shortSleepNightsForShortSession: Int = 3,
    val painReplaceThreshold: Int = 6,
    val maxAutoReschedulesPerWeek: Int = 2,
    // Progression
    val increaseAfterSessions: Int = 2,
    val maxDifficultyChangesPerWeek: Int = 1,
    val addSetAfterEasySessions: Int = 2,
    val maxSetsPerExercise: Int = 4,
    val addSetMuscleIntervalDays: Int = 14,
    /** Pain strictly below this value counts as "no pain" for adding sets. */
    val painFreeBelow: Int = 1,
    val noIncreasePainThreshold: Int = 4,
    val noIncreasePainDays: Int = 7,
    // Deload
    val deloadDropSessions: Int = 2,
    /** A session "drops" when its average reps per set is this fraction below the previous one. */
    val performanceDropFraction: Double = 0.10,
    val deloadShortSleepNights: Int = 5,
    val deloadShortSleepWindowDays: Int = 7,
    val deloadPainSessions: Int = 3,
    val deloadPainThreshold: Int = 4,
    val minDaysBetweenDeloads: Int = 21,
    // Exercise swap
    val swapPainSessions: Int = 3,
    val jointPainThreshold: Int = 4,
    // Safety
    val undoWindowHours: Int = 24,
)

@Serializable
data class Plan(
    val phases: List<Phase>,
    val deload: DeloadConfig = DeloadConfig(),
    val sessions: List<SessionTemplate>,
    /** Weekly template, keys mon..sun, values are session ids. */
    val week: Map<String, List<String>>,
    /** Default time windows per weekday, e.g. "mon": ["18:00-21:00"]. Editable in the app. */
    val timeWindows: Map<String, List<String>> = emptyMap(),
    /** Weekly working-set targets per primary muscle. */
    val muscleTargets: Map<String, Int> = emptyMap(),
    /** Session used to replace a session after a high pain report (walk or mobility). */
    val recoverySession: String? = null,
    /** General guidance shown in the plan guide. */
    val guide: List<GuideSection> = emptyList(),
    val rules: RuleConfig = RuleConfig(),
) {
    fun session(id: String): SessionTemplate? = sessions.firstOrNull { it.id == id }
}

@Serializable
data class GuideSection(val title: String, val items: List<String>)

/** Fully loaded and validated content: manifest + plan + every reachable exercise. */
data class ContentPack(
    val manifest: PackManifest,
    val plan: Plan,
    val exercises: Map<String, Exercise>,
    /** Maps an illustration file name to the folder that contains it ("pack" or "library"). */
    val illustrationRoots: Map<String, String>,
    val warnings: List<PackIssue> = emptyList(),
) {
    fun exercise(id: String): Exercise = exercises[id] ?: error("Unknown exercise '$id'")
}

val WEEKDAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
