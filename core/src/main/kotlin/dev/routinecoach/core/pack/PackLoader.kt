package dev.routinecoach.core.pack

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.routinecoach.core.engine.TimeWindow

/** Read-only view of a folder with pack files (a directory on disk, Android assets, ...). */
interface PackSource {
    fun read(path: String): String?
    fun list(dir: String): List<String>
    fun size(path: String): Long?
}

enum class Severity { ERROR, WARNING }

data class PackIssue(
    val file: String,
    val line: Int?,
    val field: String?,
    val message: String,
    val severity: Severity = Severity.ERROR,
) {
    override fun toString(): String = buildString {
        append(if (severity == Severity.ERROR) "error: " else "warning: ")
        append(file)
        if (line != null) append(":").append(line)
        if (field != null) append(" [").append(field).append("]")
        append(" ").append(message)
    }
}

sealed interface PackLoadResult {
    data class Success(val pack: ContentPack) : PackLoadResult
    data class Failure(val issues: List<PackIssue>) : PackLoadResult {
        val errors get() = issues.filter { it.severity == Severity.ERROR }
    }
}

class PackValidationException(val issues: List<PackIssue>) :
    RuntimeException(issues.joinToString("\n"))

/**
 * Loads a content pack and the exercise library, migrates old schema versions and validates everything.
 * Errors carry the file, line and field so the author can fix them quickly.
 */
class PackLoader(
    private val maxIllustrationBytes: Long = 20 * 1024,
) {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    private class Doc(val file: String, val text: String, val lines: Map<String, Int>)

    private val issues = mutableListOf<PackIssue>()

    private fun error(doc: Doc?, file: String, path: String?, message: String, severity: Severity = Severity.ERROR) {
        val line = if (doc != null && path != null) JsonLocator.lineFor(doc.lines, path) else null
        val field = path?.removePrefix("$")?.removePrefix(".")?.ifEmpty { null }
        issues += PackIssue(file, line, field, message, severity)
    }

    private fun parse(source: PackSource, file: String, label: String = file): Pair<Doc, JsonObject>? {
        val text = source.read(file)
        if (text == null) {
            issues += PackIssue(label, null, null, "file not found")
            return null
        }
        val doc = Doc(label, text, JsonLocator.lines(text))
        return try {
            val el = json.parseToJsonElement(text)
            if (el !is JsonObject) {
                error(doc, label, "$", "top level must be a JSON object"); null
            } else doc to el
        } catch (e: SerializationException) {
            val offset = Regex("offset (\\d+)").find(e.message.orEmpty())?.groupValues?.get(1)?.toInt()
            issues += PackIssue(label, offset?.let { JsonLocator.lineAtOffset(text, it) }, null,
                "invalid JSON: ${firstLine(e.message)}")
            null
        }
    }

    private inline fun <reified T> decode(doc: Doc, obj: JsonElement): T? = try {
        json.decodeFromJsonElement<T>(obj)
    } catch (e: SerializationException) {
        reportDecode(doc, e); null
    } catch (e: IllegalArgumentException) {
        reportDecode(doc, e); null
    }

    private fun reportDecode(doc: Doc, e: Exception) {
        val msg = e.message.orEmpty()
        val path = Regex("at (?:path|element):? (\\$[^\\s:,]*)").find(msg)?.groupValues?.get(1) ?: "$"
        val missing = Regex("Field '([^']+)' is required").find(msg)?.groupValues?.get(1)
            ?: Regex("Fields \\[([^\\]]+)]").find(msg)?.groupValues?.get(1)
        val unknown = Regex("unknown key '([^']+)'").find(msg)?.groupValues?.get(1)
        when {
            missing != null -> error(doc, doc.file, "$path.$missing".replace("$.$", "$."), "required field is missing")
            unknown != null -> error(doc, doc.file, "$path.$unknown".replace("..", "."), "unknown field '$unknown'")
            else -> error(doc, doc.file, path, firstLine(msg))
        }
    }

    private fun firstLine(s: String?) = s.orEmpty().lineSequence().first().replace(Regex(" at (?:path|element):? \\$\\S*"), "")

    fun load(pack: PackSource, library: PackSource?): PackLoadResult {
        issues.clear()

        // Manifest and schema version.
        val (manifestDoc, manifestObj) = parse(pack, "pack.json") ?: return PackLoadResult.Failure(issues.toList())
        val version = (manifestObj["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version == null) {
            error(manifestDoc, "pack.json", "$.schemaVersion", "schemaVersion is required and must be an integer")
            return PackLoadResult.Failure(issues.toList())
        }
        if (version > PACK_SCHEMA_VERSION) {
            error(manifestDoc, "pack.json", "$.schemaVersion",
                "pack uses schema $version but this app supports up to $PACK_SCHEMA_VERSION; update the app")
            return PackLoadResult.Failure(issues.toList())
        }
        val manifest = decode<PackManifest>(manifestDoc, PackMigrations.migrate(PackFileKind.MANIFEST, version, manifestObj))

        // Exercises: library first, then the pack (which can extend library entries).
        val rawExercises = LinkedHashMap<String, Triple<Doc, JsonObject, String>>() // id -> doc, obj, root
        fun collect(source: PackSource, root: String) {
            for (name in source.list("exercises").filter { it.endsWith(".json") }.sorted()) {
                val label = if (root == "library") "library/exercises/$name" else "exercises/$name"
                val (doc, obj) = parse(source, "exercises/$name", label) ?: continue
                val migrated = PackMigrations.migrate(PackFileKind.EXERCISE, version, obj)
                val id = (migrated["id"] as? JsonPrimitive)?.contentOrNull
                if (id == null) {
                    error(doc, label, "$.id", "required field is missing"); continue
                }
                val existing = rawExercises[id]
                if (existing != null && existing.third == root) {
                    error(doc, label, "$.id", "duplicate exercise id '$id' (also in ${existing.first.file})"); continue
                }
                rawExercises[id] = Triple(doc, migrated, root)
            }
        }
        val libraryRaw = LinkedHashMap<String, JsonObject>()
        if (library != null) {
            collect(library, "library")
            rawExercises.forEach { (id, t) -> libraryRaw[id] = t.second }
        }
        collect(pack, "pack")

        val exercises = LinkedHashMap<String, Exercise>()
        val illustrationRoots = HashMap<String, String>()
        val includeLibrary = manifest?.includeLibrary ?: true
        for ((id, t) in rawExercises) {
            val (doc, obj0, root) = t
            if (root == "library" && !includeLibrary) continue
            var obj = obj0
            val ext = (obj["extends"] as? JsonPrimitive)?.contentOrNull
            if (ext != null) {
                val base = libraryRaw[ext]
                if (base == null) {
                    error(doc, doc.file, "$.extends", "extends unknown library exercise '$ext'"); continue
                }
                obj = JsonRewrite.deepMerge(base, JsonObject(obj - "extends" + ("id" to JsonPrimitive(id))))
            }
            val ex = decode<Exercise>(doc, obj) ?: continue
            exercises[id] = ex
            for (file in listOf(ex.illustrations.start.file, ex.illustrations.end.file)) {
                illustrationRoots[file] = if (root == "pack" && pack.size("illustrations/$file") != null) "pack" else "library"
            }
            val illRoot = illustrationRoots[ex.illustrations.start.file]!!
            validateExercise(doc, ex, if (illRoot == "library") library else pack, illRoot)
        }
        for ((id, ex) in exercises) {
            val doc = rawExercises.getValue(id).first
            ex.alternatives.forEachIndexed { i, alt ->
                if (alt !in exercises) error(doc, doc.file, "$.alternatives[$i]", "unknown exercise '$alt'")
            }
        }

        // Plan.
        val planParsed = parse(pack, "plan.json")
        val plan = planParsed?.let { (doc, obj) ->
            decode<Plan>(doc, PackMigrations.migrate(PackFileKind.PLAN, version, obj))?.also { validatePlan(doc, it, exercises) }
        }

        val errors = issues.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty() || manifest == null || plan == null) return PackLoadResult.Failure(issues.toList())
        return PackLoadResult.Success(
            ContentPack(manifest, plan, exercises, illustrationRoots, issues.filter { it.severity == Severity.WARNING })
        )
    }

    private fun validateExercise(doc: Doc, ex: Exercise, illSource: PackSource?, root: String) {
        val f = doc.file
        if (!Regex("[a-z0-9_]+").matches(ex.id)) error(doc, f, "$.id", "id must use lowercase letters, digits and '_'")
        if (ex.name.isBlank()) error(doc, f, "$.name", "must not be empty")
        if (ex.primaryMuscle.isBlank()) error(doc, f, "$.primaryMuscle", "must not be empty")
        if (ex.steps.isEmpty()) error(doc, f, "$.steps", "at least one step is required")
        ex.cadence?.let { if (it.repSeconds <= 0) error(doc, f, "$.cadence", "cadence must last at least 1 s") }
        for ((key, ref) in listOf("start" to ex.illustrations.start, "end" to ex.illustrations.end)) {
            val path = "$.illustrations.$key"
            if (ref.alt.isBlank()) error(doc, f, "$path.alt", "alternative text is required")
            val size = illSource?.size("illustrations/${ref.file}")
            when {
                size == null -> error(doc, f, "$path.file", "illustration '${ref.file}' not found in $root/illustrations")
                size > maxIllustrationBytes -> error(doc, f, "$path.file", "illustration '${ref.file}' is $size bytes; limit is $maxIllustrationBytes")
            }
        }
        if (ex.illustrations.review != "approved") {
            error(doc, f, "$.illustrations.review", "illustrations not yet approved by a posture review", Severity.WARNING)
        }
    }

    private fun validatePlan(doc: Doc, plan: Plan, exercises: Map<String, Exercise>) {
        val f = doc.file
        if (plan.phases.isEmpty()) error(doc, f, "$.phases", "at least one phase is required")
        plan.phases.forEachIndexed { i, ph ->
            val p = "$.phases[$i]"
            if (ph.weeks == null && i != plan.phases.lastIndex) error(doc, f, "$p.weeks", "only the last phase can be open-ended")
            if (ph.weeks != null && ph.weeks <= 0) error(doc, f, "$p.weeks", "must be positive")
            if (ph.sets <= 0) error(doc, f, "$p.sets", "must be positive")
            if (ph.rirMin < 0 || ph.rirMin > ph.rirMax) error(doc, f, "$p.rirMin", "must be between 0 and rirMax")
        }
        plan.deload.everyWeeks?.let { if (it < 2) error(doc, f, "$.deload.everyWeeks", "must be at least 2") }
        if (plan.deload.setFactor <= 0 || plan.deload.setFactor > 1) error(doc, f, "$.deload.setFactor", "must be in (0, 1]")

        val ids = HashSet<String>()
        plan.sessions.forEachIndexed { si, s ->
            val sp = "$.sessions[$si]"
            if (!ids.add(s.id)) error(doc, f, "$sp.id", "duplicate session id '${s.id}'")
            if (s.shortDurationMin > s.durationMin) error(doc, f, "$sp.shortDurationMin", "short version cannot be longer than the full one")
            if (s.type == SessionType.STRENGTH && s.blocks.isEmpty()) error(doc, f, "$sp.blocks", "a strength session needs blocks")
            val blockIds = HashSet<String>()
            s.blocks.forEachIndexed { bi, b ->
                val bp = "$sp.blocks[$bi]"
                if (!blockIds.add(b.id)) error(doc, f, "$bp.id", "duplicate block id '${b.id}'")
                if (b.items.isEmpty()) error(doc, f, "$bp.items", "a block needs at least one item")
                b.items.forEachIndexed { ii, item ->
                    val ip = "$bp.items[$ii]"
                    val ex = exercises[item.exercise]
                    if (ex == null) error(doc, f, "$ip.exercise", "unknown exercise '${item.exercise}'")
                    if (item.range.size != 2 || item.range[0] <= 0 || item.range[0] > item.range[1]) {
                        error(doc, f, "$ip.range", "range must be [min, max] with 0 < min <= max")
                    }
                    item.sets?.let { if (it <= 0) error(doc, f, "$ip.sets", "must be positive") }
                    item.maxSets?.let { if (it <= 0) error(doc, f, "$ip.maxSets", "must be positive") }
                }
            }
            s.shortBlocks?.forEachIndexed { i, b ->
                if (b !in blockIds) error(doc, f, "$sp.shortBlocks[$i]", "unknown block '$b'")
            }
            s.stretches?.exercises?.forEachIndexed { i, e ->
                if (e !in exercises) error(doc, f, "$sp.stretches.exercises[$i]", "unknown exercise '$e'")
            }
        }
        for ((day, list) in plan.week) {
            if (day !in WEEKDAY_KEYS) error(doc, f, "$.week.$day", "unknown weekday '$day' (use ${WEEKDAY_KEYS.joinToString()})")
            list.forEachIndexed { i, sid -> if (sid !in ids) error(doc, f, "$.week.$day[$i]", "unknown session '$sid'") }
        }
        for ((day, list) in plan.timeWindows) {
            if (day !in WEEKDAY_KEYS) error(doc, f, "$.timeWindows.$day", "unknown weekday '$day'")
            list.forEachIndexed { i, w ->
                if (TimeWindow.parse(w) == null) error(doc, f, "$.timeWindows.$day[$i]", "expected HH:MM-HH:MM with start < end")
            }
        }
        plan.recoverySession?.let { r ->
            val s = plan.session(r)
            if (s == null) error(doc, f, "$.recoverySession", "unknown session '$r'")
            else if (s.type == SessionType.STRENGTH) error(doc, f, "$.recoverySession", "recovery session cannot be a strength session")
        }
        for ((m, v) in plan.muscleTargets) if (v < 0) error(doc, f, "$.muscleTargets.$m", "must not be negative")
    }
}

enum class PackFileKind { MANIFEST, EXERCISE, PLAN }

/** A migration upgrades one pack file from [from] to [from] + 1. */
fun interface PackMigration {
    fun migrate(kind: PackFileKind, obj: JsonObject): JsonObject
}

object PackMigrations {
    /** Index = source version. Migration 0 upgrades the pre-release draft format (v0) to v1. */
    val steps: List<PackMigration> = listOf(
        PackMigration { kind, obj ->
            when (kind) {
                // v0 described block items with "reps": "8-20"; v1 uses "range": [8, 20].
                PackFileKind.PLAN -> JsonRewrite.mapKey(obj, "reps") { value ->
                    "range" to JsonRewrite.rangeFromString(value.jsonPrimitive.content)
                }
                PackFileKind.MANIFEST -> JsonObject(obj + ("schemaVersion" to JsonPrimitive(1)))
                PackFileKind.EXERCISE -> obj
            }
        },
    )

    fun migrate(kind: PackFileKind, fromVersion: Int, obj: JsonObject): JsonObject {
        var cur = obj
        for (v in fromVersion until PACK_SCHEMA_VERSION) cur = steps[v].migrate(kind, cur)
        return cur
    }
}

internal object JsonRewrite {
    /** Merges [over] into [base]; nested objects are merged recursively, everything else is replaced. */
    fun deepMerge(base: JsonObject, over: JsonObject): JsonObject = JsonObject(
        (base.keys + over.keys).associateWith { k ->
            val b = base[k]
            val o = over[k]
            if (b is JsonObject && o is JsonObject) deepMerge(b, o) else (o ?: b!!)
        },
    )

    /** Recursively renames/transforms every [key] found in nested objects. */
    fun mapKey(el: JsonObject, key: String, f: (JsonElement) -> Pair<String, JsonElement>): JsonObject =
        JsonObject(el.entries.associate { (k, v) ->
            val nv = rewrite(v, key, f)
            if (k == key) f(nv) else k to nv
        })

    private fun rewrite(el: JsonElement, key: String, f: (JsonElement) -> Pair<String, JsonElement>): JsonElement = when (el) {
        is JsonObject -> mapKey(el, key, f)
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(el.map { rewrite(it, key, f) })
        else -> el
    }

    fun rangeFromString(s: String): JsonElement {
        val parts = s.split("-").map { it.trim().toInt() }
        return kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(parts.first()), JsonPrimitive(parts.last())))
    }
}
