package com.boji.app.backup

import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    @Test fun roundTripAndAuthentication() {
        val content = "薄肌：记录与关联".toByteArray()
        val encrypted = BackupCrypto.encrypt(content, "correct-password")
        assertArrayEquals(content, BackupCrypto.decrypt(encrypted, "correct-password"))
        assertTrue(runCatching { BackupCrypto.decrypt(encrypted, "wrong-password") }.isFailure)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { BackupCrypto.decrypt(encrypted, "correct-password") }.isFailure)
    }
    @Test fun everyBackupUsesNewSaltAndNonce() {
        assertFalse(BackupCrypto.encrypt(byteArrayOf(1), "password123").contentEquals(BackupCrypto.encrypt(byteArrayOf(1), "password123")))
        assertTrue(runCatching { BackupCrypto.encrypt(byteArrayOf(1), "short") }.isFailure)
    }
}
