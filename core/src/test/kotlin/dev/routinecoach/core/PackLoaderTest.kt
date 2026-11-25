package dev.routinecoach.core

import dev.routinecoach.core.pack.DirectorySource
import dev.routinecoach.core.pack.PackLoadResult
import dev.routinecoach.core.pack.PackLoader
import dev.routinecoach.core.pack.PackSource
import dev.routinecoach.core.pack.Severity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** In-memory pack for tests. */
class MemorySource(private val files: Map<String, String>) : PackSource {
    override fun read(path: String) = files[path]
    override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
    override fun size(path: String) = files[path]?.length?.toLong()
}

class PackLoaderTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "content/library").isDirectory }
    private val library = DirectorySource(File(root, "content/library"))

    private val manifest = """{ "schemaVersion": 1, "id": "t", "name": "Test" }"""
    private val exercise = """
        {
          "id": "squat",
          "name": "Squat",
          "primaryMuscle": "quads",
          "steps": ["Sit down", "Stand up"],
          "illustrations": {
            "start": { "file": "squat-start.svg", "alt": "Standing" },
            "end": { "file": "squat-end.svg", "alt": "Squatting" },
            "review": "approved"
          }
        }
    """.trimIndent()

    private fun plan(exercise: String = "squat", range: String = "[8, 12]") = """
        {
          "phases": [ { "id": "p", "name": "P", "sets": 3, "rirMin": 1, "rirMax": 2 } ],
          "sessions": [
            {
              "id": "legs",
              "name": "Legs",
              "type": "strength",
              "blocks": [ { "id": "A", "items": [ { "exercise": "$exercise", "range": $range } ] } ]
            }
          ],
          "week": { "mon": ["legs"] }
        }
    """.trimIndent()

    private fun pack(vararg overrides: Pair<String, String>) = MemorySource(
        mapOf(
            "pack.json" to manifest,
            "plan.json" to plan(),
            "exercises/squat.json" to exercise,
            "illustrations/squat-start.svg" to "<svg/>",
            "illustrations/squat-end.svg" to "<svg/>",
        ) + overrides,
    )

    private fun failure(src: PackSource): PackLoadResult.Failure {
        val r = PackLoader().load(src, null)
        return r as? PackLoadResult.Failure ?: fail("expected failure, got $r")
    }

    @Test
    fun `demo pack and library load without errors`() {
        val r = PackLoader().load(DirectorySource(File(root, "content/demo-pack")), library)
        val ok = assertIs<PackLoadResult.Success>(r, (r as? PackLoadResult.Failure)?.issues?.joinToString("\n"))
        assertTrue(ok.pack.exercises.size >= 20)
        assertTrue(ok.pack.plan.sessions.isNotEmpty())
    }

    @Test
    fun `every library exercise has text and two illustrations under 20 KB`() {
        val r = PackLoader().load(DirectorySource(File(root, "content/demo-pack")), library) as PackLoadResult.Success
        for (ex in r.pack.exercises.values) {
            assertTrue(ex.steps.isNotEmpty(), ex.id)
            for (f in listOf(ex.illustrations.start.file, ex.illustrations.end.file)) {
                val size = File(root, "content/library/illustrations/$f").length()
                assertTrue(size in 1..20 * 1024, "$f is $size bytes")
            }
        }
    }

    @Test
    fun `minimal pack loads`() {
        assertIs<PackLoadResult.Success>(PackLoader().load(pack(), null))
    }

    @Test
    fun `unknown exercise reports file, line and field`() {
        val f = failure(pack("plan.json" to plan(exercise = "lunge")))
        val issue = f.errors.single()
        assertEquals("plan.json", issue.file)
        assertEquals("sessions[0].blocks[0].items[0].exercise", issue.field)
        assertEquals(8, issue.line)
        assertTrue(issue.message.contains("lunge"))
    }

    @Test
    fun `invalid range is rejected with its line`() {
        val issue = failure(pack("plan.json" to plan(range = "[12, 8]"))).errors.single()
        assertEquals("sessions[0].blocks[0].items[0].range", issue.field)
        assertEquals(8, issue.line)
    }

    @Test
    fun `missing required field is reported`() {
        val broken = exercise.replace("\"name\": \"Squat\",\n", "")
        val issue = failure(pack("exercises/squat.json" to broken)).errors.first()
        assertEquals("exercises/squat.json", issue.file)
        assertEquals("name", issue.field)
    }

    @Test
    fun `wrong type points at the field line`() {
        val broken = exercise.replace("\"primaryMuscle\": \"quads\"", "\"primaryMuscle\": [1]")
        val issue = failure(pack("exercises/squat.json" to broken)).errors.first()
        assertEquals("primaryMuscle", issue.field)
        assertEquals(4, issue.line)
    }

    @Test
    fun `unknown field is rejected`() {
        val broken = exercise.replace("\"primaryMuscle\"", "\"primaryMusle\"")
        val issue = failure(pack("exercises/squat.json" to broken)).errors.first()
        assertEquals("primaryMusle", issue.field)
        assertEquals(4, issue.line)
    }

    @Test
    fun `syntax error reports the line`() {
        val broken = exercise.replace("\"Squat\",", "\"Squat\"")
        val issue = failure(pack("exercises/squat.json" to broken)).errors.first()
        assertEquals("exercises/squat.json", issue.file)
        assertEquals(4, issue.line)
    }

    @Test
    fun `missing or oversized illustrations fail validation`() {
        val missing = failure(MemorySource(pack().let { src -> listOf("pack.json", "plan.json", "exercises/squat.json", "illustrations/squat-end.svg").associateWith { src.read(it)!! } }))
        assertTrue(missing.errors.any { it.field == "illustrations.start.file" && it.message.contains("not found") })
        val big = failure(pack("illustrations/squat-end.svg" to "x".repeat(21 * 1024)))
        assertTrue(big.errors.any { it.field == "illustrations.end.file" && it.message.contains("limit") })
    }

    @Test
    fun `pending posture review is a warning, not an error`() {
        val r = PackLoader().load(pack("exercises/squat.json" to exercise.replace("approved", "pending")), null)
        val ok = assertIs<PackLoadResult.Success>(r)
        assertEquals(Severity.WARNING, ok.pack.warnings.single().severity)
    }

    @Test
    fun `pack exercise can extend a library exercise and translate it`() {
        val pt = """{ "id": "flexao", "extends": "push_up", "name": "Flexão", "steps": ["Desça", "Suba"] }"""
        val src = pack("exercises/flexao.json" to pt, "plan.json" to plan(exercise = "flexao"))
        val ok = assertIs<PackLoadResult.Success>(PackLoader().load(src, library))
        val ex = ok.pack.exercise("flexao")
        assertEquals("Flexão", ex.name)
        assertEquals("chest", ex.primaryMuscle)
        assertEquals("library", ok.pack.illustrationRoots[ex.illustrations.start.file])
    }

    @Test
    fun `newer schema is rejected and older schema is migrated`() {
        val newer = failure(pack("pack.json" to manifest.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")))
        assertTrue(newer.errors.single().message.contains("update the app"))

        val v0Plan = plan().replace("\"range\": [8, 12]", "\"reps\": \"8-12\"")
        val v0 = pack("pack.json" to manifest.replace("\"schemaVersion\": 1", "\"schemaVersion\": 0"), "plan.json" to v0Plan)
        val ok = assertIs<PackLoadResult.Success>(PackLoader().load(v0, null))
        assertEquals(listOf(8, 12), ok.pack.plan.sessions.single().blocks.single().items.single().range)
    }
}
