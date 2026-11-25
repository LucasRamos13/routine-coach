package dev.routinecoach.core.illustration

import dev.routinecoach.core.pack.Pose
import dev.routinecoach.core.pack.PoseSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Renders stick-figure SVG illustrations from joint angles, so every exercise shares one consistent style.
 * Output is small (a few KB) and self-contained (no external references).
 */
object PoseRenderer {
    const val SIZE = 200.0
    const val FLOOR_Y = 188.0

    private const val TORSO = 50.0
    private const val NECK = 7.0
    private const val HEAD_R = 10.0
    private const val UPPER_ARM = 28.0
    private const val FOREARM = 26.0
    private const val THIGH = 38.0
    private const val SHIN = 36.0
    private const val FOOT = 11.0
    private const val LIMB_RADIUS = 3.0

    private const val NEAR = "#37474F"
    private const val FAR = "#A7B6BE"
    private const val PROP = "#8D6E63"
    private const val BAND = "#2E7D32"
    private const val ARROW = "#E65100"
    private const val FLOOR = "#B0BEC5"

    data class Point(val x: Double, val y: Double) {
        operator fun plus(o: Point) = Point(x + o.x, y + o.y)
        operator fun minus(o: Point) = Point(x - o.x, y - o.y)
        operator fun times(k: Double) = Point(x * k, y * k)
    }

    /** Joint positions of a posed figure. */
    class Skeleton(val joints: Map<String, Point>) {
        operator fun get(name: String): Point =
            joints[name] ?: throw IllegalArgumentException("Unknown joint '$name'. Known: ${joints.keys.sorted()}")
        fun translate(d: Point) = Skeleton(joints.mapValues { it.value + d })
    }

    private fun dir(deg: Double): Point {
        val r = deg * PI / 180.0
        return Point(sin(r), -cos(r))
    }

    fun skeleton(pose: Pose): Skeleton {
        val hip = Point(0.0, 0.0)
        val shoulder = hip + dir(pose.torso) * TORSO
        val headAngle = pose.head ?: pose.torso
        val neck = shoulder + dir(headAngle) * NECK
        val head = shoulder + dir(headAngle) * (NECK + HEAD_R)
        val joints = linkedMapOf("hip" to hip, "shoulder" to shoulder, "neck" to neck, "head" to head)
        fun arm(prefix: String, a: List<Double>) {
            val elbow = shoulder + dir(a[0]) * UPPER_ARM
            joints["${prefix}Elbow"] = elbow
            joints["${prefix}Hand"] = elbow + dir(a[1]) * FOREARM
        }
        fun leg(prefix: String, l: List<Double>, foot: Double?) {
            val knee = hip + dir(l[0]) * THIGH
            val ankle = knee + dir(l[1]) * SHIN
            joints["${prefix}Knee"] = knee
            joints["${prefix}Ankle"] = ankle
            joints["${prefix}Toe"] = ankle + dir(foot ?: (l[1] - 90.0)) * FOOT
        }
        arm("near", pose.nearArm)
        arm("far", pose.farArm ?: pose.nearArm)
        leg("near", pose.nearLeg, pose.nearFoot)
        leg("far", pose.farLeg ?: pose.nearLeg, pose.farFoot ?: pose.nearFoot)
        return Skeleton(joints)
    }

    /** Positions the figure in the canvas: explicit hip, or grounded on the floor (props included) and centered. */
    fun place(pose: Pose): Skeleton {
        val raw = skeleton(pose)
        pose.hip?.let { return raw.translate(Point(it[0], it[1])) }
        val supports = pose.props.filter { it.type == "box" && it.at != null && it.height > 0 }.associate { it.at!! to it.height }
        val contactY = raw.joints.entries.maxOf { (name, p) ->
            when (name) {
                "head" -> p.y + HEAD_R
                "neck", "shoulder" -> p.y + LIMB_RADIUS + (supports[name] ?: 0.0)
                else -> p.y + LIMB_RADIUS + (supports[name] ?: 0.0)
            }
        }
        val xs = raw.joints.values.map { it.x }
        val cx = (xs.min() + xs.max()) / 2
        return raw.translate(Point(SIZE / 2 - cx, FLOOR_Y - contactY))
    }

    fun renderPair(set: PoseSet, startAlt: String, endAlt: String): Pair<String, String> {
        val s = place(set.start)
        val e0 = place(set.end)
        val e = e0.translate(Point(s[set.anchor].x - e0[set.anchor].x, 0.0))
        val start = render(set.start, s, startAlt, set.arrows.map { Arrow(s[it], e[it], dashed = false) })
        val end = render(set.end, e, endAlt, set.arrows.map { Arrow(e[it], s[it], dashed = true) })
        return start to end
    }

    data class Arrow(val from: Point, val to: Point, val dashed: Boolean)

    fun render(pose: Pose, sk: Skeleton, alt: String, arrows: List<Arrow> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200" width="200" height="200">""")
        sb.append("<title>").append(escape(alt)).append("</title>")
        sb.append("""<rect width="200" height="200" fill="#FFFFFF"/>""")
        sb.append(line(Point(6.0, FLOOR_Y), Point(194.0, FLOOR_Y), FLOOR, 2.0))

        // Background props.
        for (p in pose.props) {
            when (p.type) {
                "box" -> {
                    val j = sk[p.at!!]
                    val top = j.y + LIMB_RADIUS
                    val x = j.x - p.width / 2 + p.offset
                    val bottom = if (p.height > 0) top + p.height else FLOOR_Y
                    sb.append("""<rect x="${f(x)}" y="${f(top)}" width="${f(p.width)}" height="${f(bottom - top)}" rx="3" fill="#D7CCC8" stroke="$PROP" stroke-width="1.5"/>""")
                }
                "wall" -> {
                    val x = (p.toPoint?.get(0) ?: sk[p.at!!].x) + p.offset
                    sb.append(line(Point(x, FLOOR_Y), Point(x, FLOOR_Y - 170), PROP, 4.0))
                }
                "towel" -> {
                    val j = sk[p.at!!]
                    sb.append("""<rect x="${f(j.x - 9)}" y="${f(FLOOR_Y - 3)}" width="18" height="3" fill="#90CAF9"/>""")
                }
            }
        }

        fun limb(prefix: String, color: String) {
            sb.append(polyline(listOf(sk["shoulder"], sk["${prefix}Elbow"], sk["${prefix}Hand"]), color, 6.0))
        }
        fun leg(prefix: String, color: String) {
            sb.append(polyline(listOf(sk["hip"], sk["${prefix}Knee"], sk["${prefix}Ankle"], sk["${prefix}Toe"]), color, 6.0))
        }
        leg("far", FAR)
        limb("far", FAR)
        sb.append(line(sk["hip"], sk["shoulder"], NEAR, 9.0))
        sb.append(line(sk["shoulder"], sk["neck"], NEAR, 5.0))
        val h = sk["head"]
        sb.append("""<circle cx="${f(h.x)}" cy="${f(h.y)}" r="$HEAD_R" fill="$NEAR"/>""")
        leg("near", NEAR)
        limb("near", NEAR)

        // Foreground props.
        for (p in pose.props) {
            when (p.type) {
                "dumbbell" -> {
                    val j = sk[p.at!!]
                    sb.append("""<circle cx="${f(j.x)}" cy="${f(j.y)}" r="6.5" fill="$PROP"/>""")
                }
                "band" -> {
                    val from = sk[p.at!!]
                    val to = p.to?.let { sk[it] } ?: p.toPoint?.let { Point(it[0], it[1]) }
                        ?: throw IllegalArgumentException("band needs 'to' or 'toPoint'")
                    sb.append(line(from, to, BAND, 2.5))
                }
            }
        }

        val center = sk["hip"] + (sk["shoulder"] - sk["hip"]) * 0.5
        for (a in arrows) sb.append(arrow(a, center))
        sb.append("</svg>")
        return sb.toString()
    }

    private fun arrow(a: Arrow, bodyCenter: Point): String {
        val d = a.to - a.from
        val len = hypot(d.x, d.y)
        if (len < 4) return ""
        var n = Point(-d.y / len, d.x / len)
        val mid = a.from + d * 0.5
        // Push the arrow away from the body so it does not cover the limb.
        if (hypot((mid + n).x - bodyCenter.x, (mid + n).y - bodyCenter.y) < hypot((mid - n).x - bodyCenter.x, (mid - n).y - bodyCenter.y)) {
            n = n * -1.0
        }
        val off = 12.0
        val s = a.from + n * off + d * (0.12)
        val e = a.to + n * off - d * (0.12)
        val c = mid + n * (off + len * 0.18)
        val dash = if (a.dashed) """ stroke-dasharray="5 4"""" else ""
        // Arrowhead as a plain polygon (markers are not supported by every SVG renderer, e.g. AndroidSVG).
        val t = e - c
        val tl = hypot(t.x, t.y).coerceAtLeast(1e-6)
        val u = Point(t.x / tl, t.y / tl)
        val p = Point(-u.y, u.x)
        val tip = e + u * 4.0
        val l = e - u * 5.0 + p * 5.0
        val r = e - u * 5.0 - p * 5.0
        return """<path d="M${f(s.x)} ${f(s.y)}Q${f(c.x)} ${f(c.y)} ${f(e.x)} ${f(e.y)}" fill="none" stroke="$ARROW" stroke-width="3" stroke-linecap="round"$dash/>""" +
            """<polygon points="${f(tip.x)},${f(tip.y)} ${f(l.x)},${f(l.y)} ${f(r.x)},${f(r.y)}" fill="$ARROW"/>"""
    }

    private fun line(a: Point, b: Point, color: String, w: Double) =
        """<line x1="${f(a.x)}" y1="${f(a.y)}" x2="${f(b.x)}" y2="${f(b.y)}" stroke="$color" stroke-width="${f(w)}" stroke-linecap="round"/>"""

    private fun polyline(pts: List<Point>, color: String, w: Double) =
        """<polyline points="${pts.joinToString(" ") { "${f(it.x)},${f(it.y)}" }}" fill="none" stroke="$color" stroke-width="${f(w)}" stroke-linecap="round" stroke-linejoin="round"/>"""

    private fun f(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (abs(r - Math.round(r)) < 1e-9) Math.round(r).toString() else r.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
