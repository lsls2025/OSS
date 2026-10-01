package com.aurora.chat.data.local

import android.content.Context
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 快速切换账号管理器
 * 记录登录历史（加密存储密码），提供密码未变时的快速切换
 */
object LoginHistoryManager {

    private const val PREFS = "login_history"
    private const val KEY_RECORDS = "records"
    private const val CIPHER_KEY = "AuroraChatKey16!" // 16字节AES密钥

    data class LoginRecord(
        val email: String,
        val username: String,
        val userId: Long,
        val loginCount: Int,
        val password: String, // 加密存储的密码
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
        return try { decrypt(record.password) } catch (_: Exception) { null }
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

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(CIPHER_KEY.toByteArray(), "AES"))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(encrypted: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(CIPHER_KEY.toByteArray(), "AES"))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)))
    }

    private fun hashPassword(password: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return Base64.encodeToString(digest.digest(password.toByteArray()), Base64.NO_WRAP)
    }
}
