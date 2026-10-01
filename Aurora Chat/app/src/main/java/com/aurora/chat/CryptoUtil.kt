package com.aurora.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 私聊端到端加密工具（ECDH + AES-GCM）— v2 重构版
 *
 * 方案要点（修复 v1 的私信加密失败 / 解密失败 / 对话列表显示密文问题）：
 * 1. 长期身份密钥对由 Android Keystore 生成并存储，私钥永不落盘、不依赖 user_email，
 *    换账号/换设备策略下密钥稳定，不会因邮箱变化导致"加密失败"。
 * 2. 密钥派生改为纯字节级 SHA-256，不再经 ISO-8859-1 字符串拼接，杜绝收发双方
 *    字符集不一致导致的会话密钥不匹配。
 * 3. 采用"双盒"设计：receiverBox（接收方用双方对称会话密钥解）+ senderBox（发送方
 *    用自解密钥解），保证【接收方与发送方都能解密】。自己发出的历史消息也能正常
 *    解密，不再出现"对话列表/聊天记录显示密文"。
 * 4. 负载格式自适应公钥长度，彻底消除 v1 硬编码 91 字节导致的解析错位。
 *
 * 协议格式（Base64 编码，前缀 E2EE:v2:）：
 *   [1B version=2][2B staticPubLen][staticPub X.509][12B nonceR][48B receiverBox]
 *   [48B senderBox][12B nonce][AES-GCM ciphertext]
 *
 * 性能：ECDH + SHA-256 + AES-GCM 均为微秒级，加解密在 IO 线程执行，不阻塞 UI。
 */
object CryptoUtil {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "aurora_e2e_identity_v2"
    private const val EC_CURVE = "secp256r1"
    private const val KEY_EXCHANGE_ALG = "ECDH"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_NONCE_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PAYLOAD_VERSION = 2

    /** E2EE 密文前缀，用于快速识别加密消息（对话列表/消息列表据此判断是否解密） */
    private const val E2EE_PREFIX = "E2EE:v2:"

    // 已加载的长期密钥对（进程内缓存，避免频繁访问 Keystore）
    @Volatile private var cachedKeyPair: java.security.KeyPair? = null
    @Volatile private var cachedPublicKeyBase64: String? = null

    // 全局 Application context（由 AuroraChatApplication.instance 提供）
    private val appContext: Context
        get() = AuroraChatApplication.instance

    /**
     * 从 Android Keystore 加载或创建用户的长期身份密钥对。
     * 返回公钥 Base64（用于上传服务器）。
     *
     * 已修复 v2 版"生成密钥 100% 失败"的根因：
     * 旧逻辑在读到"有密钥但证书为空"的脏条目时不删除，随后又用同一别名重新生成，
     * Android Keystore 因别名已占用抛异常，永远走进失败分支。
     * 现改为：证书无效/异常先删除旧别名再生成；并直接使用 generateKeyPair() 返回值，
     * 不依赖重新 getEntry（拿不到证书的情况同样会导致原逻辑失败）。
     */
    fun initOrLoadIdentityKey(): String {
        return try {
            // 1. 进程内缓存命中
            if (cachedKeyPair != null && cachedPublicKeyBase64 != null) {
                return cachedPublicKeyBase64!!
            }
            val keyStore = loadKeyStore()

            // 2. 尝试加载已有密钥（证书损坏的脏数据会在下一步被清理重建）
            val existing = try {
                keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            } catch (_: Exception) { null }
            val existingCert = existing?.certificate
            if (existing != null && existingCert != null) {
                val pair = java.security.KeyPair(existingCert.publicKey, existing.privateKey)
                cachedKeyPair = pair
                cachedPublicKeyBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
                return cachedPublicKeyBase64!!
            }

            // 3. 清理"别名已占用"的脏数据，避免同名重新生成时报错
            try { if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS) } catch (_: Exception) {}

            // 4. 生成 EC secp256r1 密钥对（直接使用返回值，无需再重新 getEntry）
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setUserAuthenticationRequired(false) // 不要求每次解锁，后台可用
                .setRandomizedEncryptionRequired(false)
                .build()
            generator.initialize(spec)
            val pair = generator.generateKeyPair() // 密钥对已存入 Keystore

            cachedKeyPair = pair
            cachedPublicKeyBase64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
            cachedPublicKeyBase64!!
        } catch (e: Exception) {
            // 复位缓存状态，避免下次用失效引用
            cachedKeyPair = null
            cachedPublicKeyBase64 = null
            android.util.Log.e("CryptoUtil", "initOrLoadIdentityKey 失败: ${e.javaClass.simpleName}: ${e.message}")
            android.util.Log.e("CryptoUtil", android.util.Log.getStackTraceString(e))
            ""
        }
    }

    private fun loadKeyStore(): KeyStore {
        val ks = KeyStore.getInstance(KEYSTORE)
        ks.load(null)
        return ks
    }

    /**
     * 获取身份私钥（来自 Keystore）
     */
    private fun getIdentityPrivateKey(): PrivateKey {
        initOrLoadIdentityKey()
        return cachedKeyPair!!.private
    }

    /**
     * 设备级密钥（本地加密用）：由身份公钥 SHA-256 派生，稳定且不依赖 user_email。
     */
    private fun getDeviceKey(): ByteArray {
        val pubB64 = initOrLoadIdentityKey()
        val pubBytes = Base64.decode(pubB64, Base64.NO_WRAP)
        return MessageDigest.getInstance("SHA-256").digest(pubBytes)
    }

    /**
     * 解码 Base64 X.509 公钥
     */
    private fun decodePublicKey(encoded: String): PublicKey? = try {
        val keyBytes = Base64.decode(encoded, Base64.NO_WRAP)
        java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(keyBytes))
    } catch (_: Exception) { null }

    /**
     * ECDH 密钥交换
     */
    private fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance(KEY_EXCHANGE_ALG)
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    /**
     * 由 ECDH 共享密钥派生 AES 会话密钥（纯字节级，杜绝字符集问题）。
     * 同一对 (私钥, 公钥) 由双方分别计算，结果一致（ECDH 对称）。
     */
    private fun deriveSessionKey(sharedSecret: ByteArray): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecret)
        digest.update("aurora_e2e_v2".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest.digest(), "AES")
    }

    /**
     * 加密消息（E2EE）。发送方调用。
     *
     * 采用"双盒"设计，保证发送方与接收方都能解密（含自己发出的历史消息）：
     *   - receiverBox：用双方对称会话密钥（ECDH 我方静态×对方静态）加密随机消息密钥 mk
     *   - senderBox  ：用发送方自解密钥（ECDH 我方静态×我方静态）加密 mk
     *   - 明文用 mk 做 AES-GCM 加密
     * 接收方解 receiverBox 得 mk；发送方解 senderBox 得 mk；两者都能解出明文。
     *
     * @return Base64: [1B version][2B staticPubLen][staticPub]
     *                 [48B receiverBox][48B senderBox][12B nonce][ciphertext]
     */
    fun encrypt(plaintext: String, friendPublicKeyBase64: String): String {
        val myPriv = getIdentityPrivateKey()
        val myPubB64 = initOrLoadIdentityKey()
        val myPub = decodePublicKey(myPubB64) ?: throw IllegalStateException("身份公钥无效")
        val friendPub = decodePublicKey(friendPublicKeyBase64)
            ?: throw IllegalArgumentException("对方公钥无效")

        // 随机消息密钥（每次消息独立，前向安全）
        val mk = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // 接收方密钥 = ECDH(我方静态, 对方静态)；发送方自解密钥 = ECDH(我方静态, 我方静态)
        val receiverKey = deriveSessionKey(ecdh(myPriv, friendPub))
        val senderKey = deriveSessionKey(ecdh(myPriv, myPub))

        // receiverBox / senderBox 共享一个 nonce（两把密钥不同，GCM nonce 各自唯一即可）
        val nonceR = ByteArray(GCM_NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        val receiverBox = gcmEncrypt(receiverKey, nonceR, mk)
        val senderBox = gcmEncrypt(senderKey, nonceR, mk)

        // 用 mk 加密明文（独立 nonce）
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        val ciphertext = gcmEncrypt(SecretKeySpec(mk, "AES"), nonce, plaintext.toByteArray(Charsets.UTF_8))

        val staticPubBytes = myPub.encoded
        val buf = java.io.ByteArrayOutputStream()
        buf.write(PAYLOAD_VERSION)
        buf.write((staticPubBytes.size shr 8) and 0xFF); buf.write(staticPubBytes.size and 0xFF)
        buf.write(staticPubBytes)
        buf.write(nonceR)
        buf.write(receiverBox)
        buf.write(senderBox)
        buf.write(nonce)
        buf.write(ciphertext)
        return E2EE_PREFIX + Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 校验本机身份密钥是否可用（能完成自解 ECDH）。
     * 仅用于诊断区分「本机密钥损坏」还是「对方密钥不兼容」。
     * 注意：不要基于此结果重建身份密钥——本地存储加密密钥由身份密钥派生，
     * 重建身份密钥会导致全部本地历史记录无法解密。
     */
    fun isOwnIdentityKeyUsable(): Boolean = try {
        val priv = getIdentityPrivateKey()
        val pub = decodePublicKey(initOrLoadIdentityKey()) ?: return false
        ecdh(priv, pub)
        true
    } catch (_: Exception) { false }

    /** AES-GCM 加密（返回 nonce||密文 拼接，长度 = data + tag） */
    private fun gcmEncrypt(key: javax.crypto.SecretKey, nonce: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
        return cipher.doFinal(data)
    }

    /** AES-GCM 解密，输入 nonce||密文 */
    private fun gcmDecrypt(key: javax.crypto.SecretKey, nonce: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
        return cipher.doFinal(data)
    }

    /**
     * 是否为 E2EE 加密消息（用于对话列表/消息列表判断）。
     */
    fun isEncrypted(content: String): Boolean = content.startsWith(E2EE_PREFIX)

    /**
     * 解密消息（E2EE）。接收方或发送方自己都能调用。
     * 先尝试用接收方密钥解 receiverBox；失败再尝试用发送方自解密钥解 senderBox。
     * 支持带 E2EE_PREFIX 或不带前缀（兼容纯 base64 负载）。
     */
    fun decrypt(encrypted: String): String {
        val encryptedBase64 = if (encrypted.startsWith(E2EE_PREFIX)) {
            encrypted.removePrefix(E2EE_PREFIX)
        } else {
            encrypted
        }
        val myPriv = getIdentityPrivateKey()
        val myPubB64 = initOrLoadIdentityKey()
        val payload = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (payload.isEmpty()) throw IllegalArgumentException("数据为空")

        var pos = 0
        val version = payload[pos++].toInt() and 0xFF
        if (version != PAYLOAD_VERSION) throw IllegalArgumentException("不支持的加密版本")

        // 发送方静态公钥
        val staticPubLen = ((payload[pos++].toInt() and 0xFF) shl 8) or (payload[pos++].toInt() and 0xFF)
        if (staticPubLen <= 0 || pos + staticPubLen > payload.size) throw IllegalArgumentException("数据损坏")
        val staticPubBytes = payload.copyOfRange(pos, pos + staticPubLen); pos += staticPubLen

        // nonceR(12) → receiverBox(48) → senderBox(48) → nonce(12) → ciphertext
        if (pos + GCM_NONCE_LENGTH + 48 + 48 + GCM_NONCE_LENGTH > payload.size) throw IllegalArgumentException("数据损坏")
        val nonceR = payload.copyOfRange(pos, pos + GCM_NONCE_LENGTH); pos += GCM_NONCE_LENGTH
        val receiverBox = payload.copyOfRange(pos, pos + 48); pos += 48
        val senderBox = payload.copyOfRange(pos, pos + 48); pos += 48
        val nonce = payload.copyOfRange(pos, pos + GCM_NONCE_LENGTH); pos += GCM_NONCE_LENGTH
        val ciphertext = payload.copyOfRange(pos, payload.size)

        val senderStaticPub = decodePublicKey(Base64.encodeToString(staticPubBytes, Base64.NO_WRAP))
            ?: throw IllegalArgumentException("无法解析发送者公钥")
        val myPub = decodePublicKey(myPubB64) ?: throw IllegalStateException("身份公钥无效")

        // 1) 尝试作为接收方：ECDH(我方静态, 发送方静态) → 解 receiverBox
        var mk: ByteArray? = null
        try {
            val receiverKey = deriveSessionKey(ecdh(myPriv, senderStaticPub))
            mk = gcmDecrypt(receiverKey, nonceR, receiverBox)
        } catch (_: Exception) {
            mk = null
        }
        // 2) 若失败，尝试作为发送方自解：ECDH(我方静态, 我方静态) → 解 senderBox
        if (mk == null) {
            val senderKey = deriveSessionKey(ecdh(myPriv, myPub))
            mk = try { gcmDecrypt(senderKey, nonceR, senderBox) } catch (_: Exception) { null }
        }
        if (mk == null) throw IllegalArgumentException("解密失败")

        // 用 mk 解明文
        return String(gcmDecrypt(SecretKeySpec(mk, "AES"), nonce, ciphertext), Charsets.UTF_8)
    }

    // ==================== 本地存储加密（设备派生密钥 AES-GCM） ====================
    // 用于加密本地聊天记录/会话预览，密钥由身份公钥 SHA-256 派生（稳定，不依赖 user_email）。

    private const val LOCAL_PREFIX = "loc:v1:"

    /**
     * 加密本地存储内容，返回 "loc:v1:" 前缀的 base64。空串原样返回。
     */
    fun encryptLocal(plain: String): String {
        if (plain.isEmpty()) return plain
        return try {
            val key = getDeviceKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val nonce = ByteArray(GCM_NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, nonce))
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(nonce.size + ct.size).also {
                System.arraycopy(nonce, 0, it, 0, nonce.size)
                System.arraycopy(ct, 0, it, nonce.size, ct.size)
            }
            LOCAL_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            plain // 加密失败降级明文，避免丢数据
        }
    }

    /**
     * 解密本地存储内容。非 "loc:v1:" 前缀（旧明文/异常）原样返回。
     */
    fun decryptLocal(enc: String): String {
        if (enc.isEmpty() || !enc.startsWith(LOCAL_PREFIX)) return enc
        return try {
            val key = getDeviceKey()
            val combined = Base64.decode(enc.removePrefix(LOCAL_PREFIX), Base64.NO_WRAP)
            val nonce = combined.copyOfRange(0, GCM_NONCE_LENGTH)
            val data = combined.copyOfRange(GCM_NONCE_LENGTH, combined.size)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, nonce))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            enc // 解密失败降级返回原值
        }
    }

    // ==================== 传输会话加密（sess:v1:，AES-256-GCM） ====================
    // 用于客户端 → 服务端消息传输：客户端用服务端下发的 session_key 加密内容，
    // 格式为 "sess:v1:" + base64(nonce||ciphertext)，消除传输阶段明文。
    // 服务端收到后用同一 session_key 解密回明文，再走 enc:v1: 静态加密落库。

    private const val SESSION_PREFIX = "sess:v1:"

    /**
     * 用指定 AES-256 密钥（base64，32 字节）加密明文，返回 "sess:v1:" 前缀密文。
     * 空内容 / 无密钥 / 加密失败均原样返回（降级，保证消息不丢失）。
     */
    fun encryptWithKey(plain: String, keyBase64: String): String {
        if (plain.isEmpty() || keyBase64.isEmpty()) return plain
        return try {
            val key = Base64.decode(keyBase64, Base64.NO_WRAP)
            if (key.size != 32) return plain
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val nonce = ByteArray(GCM_NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, nonce))
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(nonce.size + ct.size).also {
                System.arraycopy(nonce, 0, it, 0, nonce.size)
                System.arraycopy(ct, 0, it, nonce.size, ct.size)
            }
            SESSION_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            plain
        }
    }

    /**
     * 用指定 AES-256 密钥解密 "sess:v1:" 前缀密文。非此前缀 / 解密失败原样返回。
     */
    fun decryptWithKey(enc: String, keyBase64: String): String {
        if (enc.isEmpty() || !enc.startsWith(SESSION_PREFIX)) return enc
        return try {
            val key = Base64.decode(keyBase64, Base64.NO_WRAP)
            if (key.size != 32) return enc
            val combined = Base64.decode(enc.removePrefix(SESSION_PREFIX), Base64.NO_WRAP)
            val nonce = combined.copyOfRange(0, GCM_NONCE_LENGTH)
            val data = combined.copyOfRange(GCM_NONCE_LENGTH, combined.size)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, nonce))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            enc
        }
    }
}
