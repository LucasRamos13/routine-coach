package dev.routinecoach.app.reminders

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal ntfy client. Reminders go to `<topic>`; acknowledgements from any channel go to `<topic>-ack` as
 * `ack:<reminderId>:<done|snooze>:<channel>`. Messages carry no health data, only a generic text and a time.
 */
class Relay(private val server: String, private val topic: String) {
    data class Ack(val reminderId: Long, val kind: String, val channel: String, val time: Long)

    private val base = server.trimEnd('/')
    val ackTopic get() = "$topic-ack"

    fun publishReminder(reminderId: Long, timeText: String) {
        val actions = listOf("done", "snooze").joinToString("; ") { kind ->
            "http, ${kind.replaceFirstChar { it.uppercase() }}, $base/$ackTopic, method=POST, body=ack:$reminderId:$kind:ntfy, clear=true"
        }
        post("$base/$topic", "Training session at $timeText. Reply done or snooze.", mapOf(
            "Title" to "Training reminder",
            "Tags" to "rc-$reminderId",
            "Actions" to actions,
        ))
    }

    fun publishAck(reminderId: Long, kind: String, channel: String) =
        post("$base/$ackTopic", "ack:$reminderId:$kind:$channel", emptyMap())

    fun pollAcks(sinceEpochSec: Long): List<Ack> {
        val since = if (sinceEpochSec > 0) sinceEpochSec.toString() else "12h"
        val c = URL("$base/$ackTopic/json?poll=1&since=$since").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        return try {
            if (c.responseCode !in 200..299) error("ntfy returned ${c.responseCode}")
            c.inputStream.bufferedReader().readLines().mapNotNull { line -> parse(line) }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(line: String): Ack? = runCatching {
        val o = Json.parseToJsonElement(line) as JsonObject
        if (o["event"]?.jsonPrimitive?.contentOrNull != "message") return null
        val msg = o["message"]?.jsonPrimitive?.contentOrNull ?: return null
        val p = msg.trim().split(":")
        if (p.size < 3 || p[0] != "ack") return null
        Ack(p[1].toLong(), p[2], p.getOrNull(3) ?: "unknown", o["time"]?.jsonPrimitive?.longOrNull ?: 0)
    }.getOrNull()

    private fun post(url: String, body: String, headers: Map<String, String>) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) error("ntfy returned ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }
}
