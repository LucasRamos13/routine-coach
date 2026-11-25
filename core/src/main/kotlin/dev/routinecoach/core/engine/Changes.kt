package dev.routinecoach.core.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An atomic, reversible change produced by the automation engine. Dates are ISO-8601 strings so the
 * change can be stored as JSON and undone later.
 */
@Serializable
sealed interface Change {
    /** Returns the change that reverts this one. */
    fun inverse(): Change

    @Serializable @SerialName("move")
    data class MoveEntry(val entryId: Long, val from: String, val to: String) : Change {
        override fun inverse() = MoveEntry(entryId, to, from)
    }

    @Serializable @SerialName("variant")
    data class SetVariant(val entryId: Long, val from: Variant, val to: Variant) : Change {
        override fun inverse() = SetVariant(entryId, to, from)
    }

    @Serializable @SerialName("replace")
    data class ReplaceSession(val entryId: Long, val from: String, val to: String) : Change {
        override fun inverse() = ReplaceSession(entryId, to, from)
    }

    @Serializable @SerialName("status")
    data class SetStatus(val entryId: Long, val from: EntryStatus, val to: EntryStatus) : Change {
        override fun inverse() = SetStatus(entryId, to, from)
    }

    @Serializable @SerialName("level")
    data class SetLevel(val exerciseId: String, val from: Int, val to: Int) : Change {
        override fun inverse() = SetLevel(exerciseId, to, from)
    }

    @Serializable @SerialName("extraSets")
    data class SetExtraSets(val exerciseId: String, val from: Int, val to: Int) : Change {
        override fun inverse() = SetExtraSets(exerciseId, to, from)
    }

    @Serializable @SerialName("deload")
    data class Deload(val from: String, val to: String, val remove: Boolean = false) : Change {
        override fun inverse() = copy(remove = !remove)
    }

    /** Replaces [exerciseId] by [to] in every session; the original stops progressing while swapped. */
    @Serializable @SerialName("swap")
    data class SwapExercise(val exerciseId: String, val from: String?, val to: String?) : Change {
        override fun inverse() = SwapExercise(exerciseId, to, from)
    }
}

enum class Direction { REDUCTION, INCREASE, NEUTRAL }

/** A rule outcome: changes to apply plus the explanation shown to the user. */
data class Decision(
    val rule: String,
    val title: String,
    val reason: String,
    /** The data the rule used, shown with the notice ("what did the app look at?"). */
    val data: Map<String, String>,
    val changes: List<Change>,
    val direction: Direction,
    /** Counts toward the weekly limit of automatic reschedules. */
    val countsAsReschedule: Boolean = false,
) {
    val isNoticeOnly get() = changes.isEmpty()
}

object ChangeCodec {
    private val json = Json { classDiscriminator = "kind" }
    fun encode(changes: List<Change>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Change.serializer()), changes)
    fun decode(s: String): List<Change> = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Change.serializer()), s)
}

object Rules {
    const val MISSED = "calendar.missed"
    const val UNAVAILABLE = "calendar.unavailable"
    const val SLEEP_SHORT = "calendar.sleep-short"
    const val PAIN_RECOVERY = "calendar.pain-recovery"
    const val INCREASE = "progression.increase"
    const val ADD_SET = "progression.add-set"
    const val DELOAD = "progression.deload"
    const val SWAP = "progression.swap"
}
