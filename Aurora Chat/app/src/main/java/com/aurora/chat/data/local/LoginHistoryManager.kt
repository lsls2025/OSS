package com.aurora.chat.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 快速切换账号管理器
 * 记录登录历史（加密存储密码），提供密码未变时的快速切换
 *
 * 安全（密钥清理）：加密密钥不再硬编码在源码里（硬编码 AES 密钥随 APK 一起分发，
 * 反编译提取后密文等于明文）。改为 AndroidKeyStore 生成的 AES/GCM 密钥：
 * 密钥由系统硬件级密钥库保管、不可导出，仅在本人设备上可加解密。
 */
object LoginHistoryManager {

    private const val PREFS = "login_history"
    private const val KEY_RECORDS = "records"
    private const val KEYSTORE_ALIAS = "aurora_login_history_aes_v1"

    data class LoginRecord(
        val email: String,
        val username: String,
        val userId: Long,
        val loginCount: Int,
        val password: String, // 加密存储的密码（AndroidKeyStore AES/GCM，格式 base64(iv):base64(密文)）
        val qq: String = ""
    )

    fun recordLogin(context: Context, email: String, username: String, userId: Long, password: String, qq: String = "") {
        val records = getRecords(context).toMutableList()
        val idx = records.indexOfFirst { it.email == email }
        val encPwd = encrypt(password)
        if (idx >= 0) {
            val old = records[idx]
            records[idx] = old.copy(loginCount = old.loginCount + 1, password = encPwd, username = username, userId = userId, qq = qq)
        } else {
            records.add(LoginRecord(email, username, userId, 1, encPwd, qq))
        }
        saveRecords(context, records)
    }

    fun getQuickSwitchAccounts(context: Context, currentEmail: String): List<LoginRecord> {
        return getRecords(context).filter { it.email != currentEmail && it.loginCount >= 2 }
    }

    fun getPassword(context: Context, email: String): String? {
        val record = getRecords(context).find { it.email == email } ?: return null
        return try {
            decrypt(record.password).ifBlank { null }
        } catch (_: Exception) { null }
    }

    fun invalidatePassword(context: Context, email: String) {
        val records = getRecords(context).toMutableList()
        records.removeAll { it.email == email }
        saveRecords(context, records)
    }

    private fun getRecords(context: Context): MutableList<LoginRecord> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                LoginRecord(
                    email = obj.getString("email"),
                    username = obj.getString("username"),
                    userId = obj.getLong("userId"),
                    loginCount = obj.getInt("loginCount"),
                    password = obj.getString("password"),
                    qq = obj.optString("qq", "")
                )
            }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveRecords(context: Context, records: List<LoginRecord>) {
        val arr = org.json.JSONArray()
        records.forEach { r ->
            arr.put(org.json.JSONObject().apply {
                put("email", r.email)
                put("username", r.username)
                put("userId", r.userId)
                put("loginCount", r.loginCount)
                put("password", r.password)
                put("qq", r.qq)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    // ---------------- AndroidKeyStore 加密（密钥不可导出） ----------------

    /** 获取（必要时首次生成）AndroidKeyStore 中的 AES-256-GCM 密钥。 */
    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密为 base64(iv):base64(密文)。旧版（硬编码密钥 AES/ECB）密文无冒号分隔符，解密时自然失败并按无密码处理。 */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
    }

    private fun decrypt(encrypted: String): String {
        val sep = encrypted.indexOf(':')
        if (sep <= 0) return "" // 旧格式/损坏数据：按无密码处理
        val iv = Base64.decode(encrypted.substring(0, sep), Base64.NO_WRAP)
        val enc = Base64.decode(encrypted.substring(sep + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(enc), Charsets.UTF_8)
    }

    private fun hashPassword(password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return Base64.encodeToString(digest.digest(password.toByteArray()), Base64.NO_WRAP)
    }
}
