package dev.routinecoach.app.backup

import androidx.room.withTransaction
import dev.routinecoach.app.data.AppDatabase
import dev.routinecoach.app.data.AutomationChangeEntity
import dev.routinecoach.app.data.BodyMetricEntity
import dev.routinecoach.app.data.CalendarEntryEntity
import dev.routinecoach.app.data.DeloadRangeEntity
import dev.routinecoach.app.data.ExerciseEventEntity
import dev.routinecoach.app.data.ExerciseStateEntity
import dev.routinecoach.app.data.GeneratedWeekEntity
import dev.routinecoach.app.data.SessionLogEntity
import dev.routinecoach.app.data.SetLogEntity
import dev.routinecoach.app.data.UnavailableDayEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Every user table. The JSON export and the encrypted backup share this format. */
@Serializable
data class BackupData(
    val format: Int = 1,
    val exportedAt: String,
    val calendar: List<CalendarEntryEntity>,
    val generatedWeeks: List<GeneratedWeekEntity>,
    val sessions: List<SessionLogEntity>,
    val sets: List<SetLogEntity>,
    val exerciseEvents: List<ExerciseEventEntity>,
    val bodyMetrics: List<BodyMetricEntity>,
    val exerciseStates: List<ExerciseStateEntity>,
    val automationChanges: List<AutomationChangeEntity>,
    val deloads: List<DeloadRangeEntity>,
    val unavailableDays: List<UnavailableDayEntity>,
)

class BackupManager(private val db: AppDatabase) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun snapshot(): BackupData = BackupData(
        exportedAt = java.time.LocalDateTime.now().toString(),
        calendar = db.calendar().all(),
        generatedWeeks = db.calendar().allWeeks(),
        sessions = db.logs().allSessions(),
        sets = db.logs().allSets(),
        exerciseEvents = db.logs().allEvents(),
        bodyMetrics = db.body().all(),
        exerciseStates = db.states().all(),
        automationChanges = db.states().allChanges(),
        deloads = db.backup().allDeloads(),
        unavailableDays = db.calendar().unavailable(),
    )

    suspend fun exportJson(out: OutputStream) {
        out.write(json.encodeToString(BackupData.serializer(), snapshot()).toByteArray())
    }

    /** One CSV file per table inside a zip. */
    suspend fun exportCsv(out: OutputStream) {
        val tree = Json.encodeToJsonElement(snapshot()) as JsonObject
        ZipOutputStream(out).use { zip ->
            for ((name, value) in tree) {
                val rows = (value as? JsonArray)?.map { it as JsonObject } ?: continue
                zip.putNextEntry(ZipEntry("$name.csv"))
                zip.write(toCsv(rows).toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun toCsv(rows: List<JsonObject>): String {
        if (rows.isEmpty()) return ""
        val cols = rows.flatMap { it.keys }.distinct()
        fun cell(v: kotlinx.serialization.json.JsonElement?): String {
            val s = when (v) {
                null, kotlinx.serialization.json.JsonNull -> ""
                is JsonPrimitive -> v.content
                else -> v.toString()
            }
            return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
        return buildString {
            appendLine(cols.joinToString(","))
            rows.forEach { r -> appendLine(cols.joinToString(",") { cell(r[it]) }) }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Encrypted backup: "RCBK1" + salt(16) + iv(12) + AES-256-GCM(JSON). Key = PBKDF2-HMAC-SHA256(password).
    // ------------------------------------------------------------------------------------------

    suspend fun exportEncrypted(out: OutputStream, password: CharArray) {
        val plain = Json.encodeToString(BackupData.serializer(), snapshot()).toByteArray()
        out.write(BackupCrypto.encrypt(plain, password))
    }

    suspend fun restoreEncrypted(bytes: ByteArray, password: CharArray) {
        val data = Json { ignoreUnknownKeys = true }.decodeFromString(BackupData.serializer(), String(BackupCrypto.decrypt(bytes, password)))
        restore(data)
    }

    suspend fun restore(d: BackupData) {
        val b = db.backup()
        db.withTransaction {
            b.clearCalendar(); b.clearWeeks(); b.clearSessions(); b.clearSets(); b.clearEvents(); b.clearBody()
            b.clearStates(); b.clearChanges(); b.clearDeloads(); b.clearUnavailable(); b.clearReminders()
            b.insertCalendar(d.calendar); b.insertWeeks(d.generatedWeeks); b.insertSessions(d.sessions); b.insertSets(d.sets)
            b.insertEvents(d.exerciseEvents); b.insertBody(d.bodyMetrics); b.insertStates(d.exerciseStates)
            b.insertChanges(d.automationChanges); b.insertDeloads(d.deloads); b.insertUnavailable(d.unavailableDays)
        }
    }
}

object BackupCrypto {
    private val MAGIC = "RCBK1".toByteArray()
    private const val ITERATIONS = 120_000

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(password, salt, ITERATIONS, 256)).encoded, "AES")
    }

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val out = ByteArrayOutputStream()
        out.write(MAGIC); out.write(salt); out.write(iv); out.write(c.doFinal(plain))
        return out.toByteArray()
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        require(data.size > MAGIC.size + 28 && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a Routine Coach backup" }
        var p = MAGIC.size
        val salt = data.copyOfRange(p, p + 16); p += 16
        val iv = data.copyOfRange(p, p + 12); p += 12
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return try {
            c.doFinal(data, p, data.size - p)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("Wrong password or damaged file")
        }
    }
}
