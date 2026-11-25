package dev.routinecoach.tools

import dev.routinecoach.core.illustration.PoseRenderer
import dev.routinecoach.core.pack.DirectorySource
import dev.routinecoach.core.pack.Exercise
import dev.routinecoach.core.pack.PackLoadResult
import dev.routinecoach.core.pack.PackLoader
import dev.routinecoach.core.pack.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """routine-coach-tools <command>

Commands:
  validate --pack <dir> [--library <dir>] [--strict]
      Validates a content pack. Exits with 1 on errors (and on warnings with --strict).
  render --dir <dir>
      Generates illustrations/<id>-start.svg and -end.svg for every exercise in <dir>/exercises
      that defines "poses".
"""

fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: run { println(USAGE); exitProcess(2) }
    val opts = args.drop(1)
    fun opt(name: String): String? = opts.indexOf(name).takeIf { it >= 0 }?.let { opts.getOrNull(it + 1) }
    when (cmd) {
        "validate" -> {
            val pack = File(opt("--pack") ?: fail("--pack is required"))
            val library = opt("--library")?.let { DirectorySource(File(it)) }
            when (val r = PackLoader().load(DirectorySource(pack), library)) {
                is PackLoadResult.Success -> {
                    r.pack.warnings.forEach { println(it) }
                    println("OK: '${r.pack.manifest.name}' with ${r.pack.plan.sessions.size} sessions and ${r.pack.exercises.size} exercises")
                    if ("--strict" in opts && r.pack.warnings.isNotEmpty()) exitProcess(1)
                }
                is PackLoadResult.Failure -> {
                    r.issues.sortedBy { it.severity != Severity.ERROR }.forEach { System.err.println(it) }
                    System.err.println("FAILED: ${r.errors.size} error(s)")
                    exitProcess(1)
                }
            }
        }
        "render" -> render(File(opt("--dir") ?: fail("--dir is required")))
        else -> { println(USAGE); exitProcess(2) }
    }
}

private fun fail(msg: String): Nothing {
    System.err.println(msg); exitProcess(2)
}

private fun render(dir: File) {
    val json = Json { ignoreUnknownKeys = true }
    val out = File(dir, "illustrations").apply { mkdirs() }
    var count = 0
    for (f in File(dir, "exercises").listFiles { x -> x.extension == "json" }.orEmpty().sortedBy { it.name }) {
        val obj = json.parseToJsonElement(f.readText()) as JsonObject
        if ("poses" !in obj) continue
        val ex = json.decodeFromJsonElement(Exercise.serializer(), JsonObject(obj - "extends" + ("id" to (obj["id"] ?: JsonPrimitive(f.nameWithoutExtension)))))
        val poses = ex.poses ?: continue
        val (start, end) = PoseRenderer.renderPair(poses, ex.illustrations.start.alt, ex.illustrations.end.alt)
        File(out, ex.illustrations.start.file).writeText(start)
        File(out, ex.illustrations.end.file).writeText(end)
        count++
    }
    println("Rendered $count exercise(s) into $out")
}
