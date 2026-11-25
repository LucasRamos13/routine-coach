package dev.routinecoach.core.pack

import kotlinx.serialization.Serializable

/**
 * Pose description used to generate exercise illustrations.
 *
 * All angles are absolute segment directions in degrees, in a side view with the figure facing right:
 * 0 = pointing up, 90 = pointing forward (right), 180 = pointing down, 270 = pointing backward.
 * Limbs are given as [upper segment, lower segment]: arm = [upper arm, forearm], leg = [thigh, shin].
 * The "near" limbs are drawn dark, the "far" limbs light. When a far limb is omitted it mirrors the near one.
 */
@Serializable
data class Pose(
    val torso: Double = 0.0,
    val head: Double? = null,
    val nearArm: List<Double> = listOf(180.0, 180.0),
    val farArm: List<Double>? = null,
    val nearLeg: List<Double> = listOf(180.0, 180.0),
    val farLeg: List<Double>? = null,
    /** Foot direction; defaults to perpendicular to the shin, pointing forward. */
    val nearFoot: Double? = null,
    val farFoot: Double? = null,
    /** Explicit hip position in the 200x200 canvas. When absent the figure is grounded and centered. */
    val hip: List<Double>? = null,
    val props: List<Prop> = emptyList(),
)

/**
 * Simple props attached to joints. Joint names: hip, shoulder, head, nearElbow, nearHand, farElbow, farHand,
 * nearKnee, nearAnkle, nearToe, farKnee, farAnkle, farToe.
 *
 * - dumbbell: drawn at [at].
 * - band: line from [at] to [to] (joint) or [toPoint] (absolute canvas point).
 * - box: support (sofa, step, bench) under [at], [width] wide and shifted by [offset]. With [height] > 0 the box
 *   lifts the figure by that height (e.g. a step under the feet); with height 0 it fills the gap down to the floor.
 * - towel: flat towel under [at].
 * - wall: vertical wall at the x of [at] (or [toPoint]) shifted by [offset].
 */
@Serializable
data class Prop(
    val type: String,
    val at: String? = null,
    val to: String? = null,
    val toPoint: List<Double>? = null,
    val height: Double = 0.0,
    val width: Double = 40.0,
    val offset: Double = 0.0,
)

@Serializable
data class PoseSet(
    val start: Pose,
    val end: Pose,
    /** Joints whose path from start to end gets a movement arrow. */
    val arrows: List<String> = emptyList(),
    /** Joint kept at the same horizontal position in both frames (e.g. the planted foot). */
    val anchor: String = "nearAnkle",
)
