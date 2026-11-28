package dev.routinecoach.app.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupCryptoTest {
    private val plain = """{"format":1,"hello":"world"}""".toByteArray()
    private val password = "correct horse".toCharArray()

    @Test
    fun roundTripUsesV2HeaderWithStoredIterations() {
        val enc = BackupCrypto.encrypt(plain, password.copyOf())
        assertEquals("RCBK2", String(enc.copyOfRange(0, 5)))
        assertEquals(1, enc[5].toInt())
        assertEquals(BackupCrypto.ITERATIONS, java.nio.ByteBuffer.wrap(enc, 6, 4).int)
        assertContentEquals(plain, BackupCrypto.decrypt(enc, password.copyOf()))
    }

    @Test
    fun customIterationCountIsReadBackFromHeader() {
        val enc = BackupCrypto.encrypt(plain, password.copyOf(), iterations = 1_000)
        assertContentEquals(plain, BackupCrypto.decrypt(enc, password.copyOf()))
    }

    @Test
    fun wrongPasswordFails() {
        val enc = BackupCrypto.encrypt(plain, password.copyOf(), iterations = 1_000)
        assertFailsWith<IllegalArgumentException> { BackupCrypto.decrypt(enc, "nope".toCharArray()) }
    }

    @Test
    fun tamperedIterationCountFailsAuthentication() {
        val enc = BackupCrypto.encrypt(plain, password.copyOf(), iterations = 1_000)
        java.nio.ByteBuffer.wrap(enc).putInt(6, 1_001)
        assertFailsWith<IllegalArgumentException> { BackupCrypto.decrypt(enc, password.copyOf()) }
    }

    @Test
    fun legacyV1BackupStillRestores() {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.copyOf(), salt, 120_000, 256)).encoded, "AES",
        )
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        val v1 = "RCBK1".toByteArray() + salt + iv + c.doFinal(plain)
        assertContentEquals(plain, BackupCrypto.decrypt(v1, password.copyOf()))
    }

    @Test
    fun rejectsForeignFiles() {
        assertFailsWith<IllegalArgumentException> { BackupCrypto.decrypt("not a backup at all".toByteArray(), password) }
    }
}
