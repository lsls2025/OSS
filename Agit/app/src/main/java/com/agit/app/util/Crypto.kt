package com.agit.app.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 密码哈希(PBKDF2-HMAC-SHA256)、摘要与随机令牌。零第三方依赖。
 */
object Crypto {
    private const val ITERATIONS = 120_000
    private const val KEY_LEN = 32
    private const val SALT_LEN = 16

    fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(data)
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) sb.append("%02x".format(b))
        return sb.toString()
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** 规范化序列化为稳定哈希串（用于 tree / commit id）。 */
    fun stableHash(vararg parts: String): String = sha256Hex(parts.joinToString("|"))

    fun hashPassword(password: String): String {
        val salt = ByteArray(SALT_LEN)
        SecureRandom().nextBytes(salt)
        val key = pbkdf2(password, salt, ITERATIONS, KEY_LEN)
        return "pbkdf2|$ITERATIONS|" + b64(salt) + "|" + b64(key)
    }

    fun verifyPassword(password: String, stored: String): Boolean {
        if (stored.isBlank()) return false
        val parts = stored.split("|")
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = try { Base64.getDecoder().decode(parts[2]) } catch (e: Exception) { return false }
        val expected = try { Base64.getDecoder().decode(parts[3]) } catch (e: Exception) { return false }
        val actual = pbkdf2(password, salt, iterations, expected.size)
        return constantTimeEquals(actual, expected)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, length * 8)
        return factory.generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        val sb = StringBuilder(buf.size * 2)
        for (b in buf) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** 由种子稳定派生头像配色索引 [0..7]。 */
    fun avatarColorIndex(seed: String): Int {
        val h = sha256Hex(seed.ifEmpty { "?" })
        return kotlin.math.abs(h.substring(0, 8).toLong(16).toInt()) % 8
    }

    private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
}
