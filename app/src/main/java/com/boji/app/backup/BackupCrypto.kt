package com.boji.app.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Self-contained password backup; no device-only key or model credentials. */
object BackupCrypto {
    private val magic = "BOJI01".toByteArray(Charsets.US_ASCII)
    private const val iterations = 210_000
    const val MAX_BYTES = 64 * 1024 * 1024

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        require(password.length >= 8) { "备份密码至少 8 位" }
        require(plain.size <= MAX_BYTES - 128) { "备份过大" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        return magic + salt + nonce + cipher.doFinal(plain)
    }

    fun decrypt(bytes: ByteArray, password: String): ByteArray {
        require(bytes.size in (magic.size + 16 + 12 + 16)..MAX_BYTES) { "备份文件无效" }
        val input = ByteBuffer.wrap(bytes)
        val signature = ByteArray(magic.size).also(input::get)
        require(signature.contentEquals(magic)) { "这不是薄肌备份" }
        val salt = ByteArray(16).also(input::get)
        val nonce = ByteArray(12).also(input::get)
        val payload = ByteArray(input.remaining()).also(input::get)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
            cipher.updateAAD(magic)
            return cipher.doFinal(payload)
        } catch (_: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("密码错误或文件损坏")
        }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }
}
