package com.aurora.chat.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/**
 * APK 签名校验工具
 * 校验当前 App 的签名指纹是否与正版一致，防止篡改/盗版。
 *
 * 使用方法：
 * 1. 首次运行正式签名 APK，运行一次下方 printActualSignature() 获取 SHA256
 * 2. 将输出的 SHA256 填入 generateEncrypted(arrayOf(...)) 生成加密数组
 * 3. 将生成的加密数组替换 ENCRYPTED_SIGNATURE
 */
object SignatureValidator {

    private const val TAG = "SignatureValidator"

    // XOR 密钥（一个字节）
    private const val XOR_KEY = 0x7C.toByte()

    //  加密后的签名指纹（SHA-256 十六进制字符串 异或 XOR_KEY 加密）
    // 生成方法：运行一次 printActualSignature() 获取原始 SHA256，然后调用 generateEncrypted()
    // 默认值已预置占位，需要替换为实际签名后生成的加密数组
    private val ENCRYPTED_SIGNATURE: ByteArray = byteArrayOf()

    /** 校验当前 APK 签名是否为正版 */
    fun verify(context: Context): Boolean {
        try {
            val actualHex = getSignatureHex(context) ?: return false
            if (ENCRYPTED_SIGNATURE.isEmpty()) {
                Log.w(TAG, "ENCRYPTED_SIGNATURE 为空，跳过校验")
                return true // 开发模式跳过
            }
            val expectedHex = decryptSignature()
            val match = actualHex.equals(expectedHex, ignoreCase = true)
            if (!match) {
                Log.e(TAG, "签名校验失败！当前: $actualHex, 期望: $expectedHex")
            }
            return match
        } catch (e: Exception) {
            Log.e(TAG, "签名校验异常: ${e.message}")
            return false
        }
    }

    /** 获取 APK 签名的 SHA-256 十六进制字符串 */
    private fun getSignatureHex(context: Context): String? {
        val pm = context.packageManager
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val certBytes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(certBytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** 解密存储的签名指纹 */
    private fun decryptSignature(): String {
        val decrypted = ENCRYPTED_SIGNATURE.map { (it.toInt() xor XOR_KEY.toInt()).toByte() }.toByteArray()
        return String(decrypted, Charsets.UTF_8)
    }

    /** 生成加密数组（用于开发者工具：将原始 SHA256 转为加密后数组） */
    @JvmStatic
    fun generateEncrypted(originalHex: String): String {
        val bytes = originalHex.toByteArray(Charsets.UTF_8)
        val encrypted = bytes.map { (it.toInt() xor XOR_KEY.toInt()).toByte() }
        return encrypted.joinToString(", ") { it.toInt().toString() }
    }

    /** 打印当前 APK 的真实签名（用于开发者获取原始 SHA256） */
    @JvmStatic
    fun printActualSignature(context: Context) {
        val hex = getSignatureHex(context)
        if (hex != null) {
            Log.i(TAG, "当前 APK 签名 SHA256: $hex")
            Log.i(TAG, "加密数组: ${generateEncrypted(hex)}")
            Log.i(TAG, "请将上述加密数组替换到 ENCRYPTED_SIGNATURE 中")
        } else {
            Log.e(TAG, "获取签名失败")
        }
    }
}
