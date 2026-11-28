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
    // Encrypted backup. See BackupCrypto for the on-disk format (KDF parameters are stored in the
    // file header, so raising the cost later never breaks restoring older backups).
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

/**
 * Password-based encryption for backups.
 *
 * Format v2 (current):
 *   "RCBK2" | kdfId(1) | iterations(4, big-endian) | salt(16) | iv(12) | AES-256-GCM(JSON, 128-bit tag)
 * The whole header (everything before the ciphertext) is authenticated as GCM associated data, so the
 * stored KDF parameters cannot be tampered with without failing decryption.
 *
 * Format v1 (legacy, read-only): "RCBK1" | salt(16) | iv(12) | ciphertext, PBKDF2 with 120 000 iterations.
 *
 * KDF: PBKDF2-HMAC-SHA256 at 600 000 iterations, OWASP's current recommendation for PBKDF2-SHA256.
 * Argon2id would be preferable but has no platform implementation on Android; the kdfId byte leaves room to
 * switch to it later without another format change.
 */
object BackupCrypto {
    private val MAGIC_V1 = "RCBK1".toByteArray()
    private val MAGIC_V2 = "RCBK2".toByteArray()
    private const val KDF_PBKDF2_SHA256: Byte = 1
    const val ITERATIONS = 600_000
    private const val LEGACY_ITERATIONS = 120_000
    /** Upper bound accepted when reading, so a crafted file cannot make restore hang. */
    private const val MAX_ITERATIONS = 10_000_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(rnd::nextBytes)
        val iv = ByteArray(IV_LEN).also(rnd::nextBytes)
        val header = java.nio.ByteBuffer.allocate(MAGIC_V2.size + 1 + 4 + SALT_LEN + IV_LEN)
            .put(MAGIC_V2).put(KDF_PBKDF2_SHA256).putInt(iterations).put(salt).put(iv).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(header)
        return header + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        val magic = if (data.size >= MAGIC_V2.size) data.copyOfRange(0, MAGIC_V2.size) else ByteArray(0)
        return when {
            magic.contentEquals(MAGIC_V2) -> decryptV2(data, password)
            magic.contentEquals(MAGIC_V1) -> decryptV1(data, password)
            else -> throw IllegalArgumentException("Not a Routine Coach backup")
        }
    }

    private fun decryptV2(data: ByteArray, password: CharArray): ByteArray {
        val headerLen = MAGIC_V2.size + 1 + 4 + SALT_LEN + IV_LEN
        require(data.size > headerLen + TAG_BITS / 8) { "Not a Routine Coach backup" }
        val buf = java.nio.ByteBuffer.wrap(data, MAGIC_V2.size, headerLen - MAGIC_V2.size)
        val kdf = buf.get()
        val iterations = buf.int
        require(kdf == KDF_PBKDF2_SHA256) { "Unsupported backup key derivation ($kdf); update the app" }
        require(iterations in 1..MAX_ITERATIONS) { "Wrong password or damaged file" }
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val iv = ByteArray(IV_LEN).also { buf.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(data, 0, headerLen)
        return finish(c, data, headerLen)
    }

    private fun decryptV1(data: ByteArray, password: CharArray): ByteArray {
        require(data.size > MAGIC_V1.size + SALT_LEN + IV_LEN) { "Not a Routine Coach backup" }
        var p = MAGIC_V1.size
        val salt = data.copyOfRange(p, p + SALT_LEN); p += SALT_LEN
        val iv = data.copyOfRange(p, p + IV_LEN); p += IV_LEN
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt, LEGACY_ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        return finish(c, data, p)
    }

    private fun finish(c: Cipher, data: ByteArray, offset: Int): ByteArray = try {
        c.doFinal(data, offset, data.size - offset)
    } catch (e: javax.crypto.AEADBadTagException) {
        throw IllegalArgumentException("Wrong password or damaged file")
    }
}
