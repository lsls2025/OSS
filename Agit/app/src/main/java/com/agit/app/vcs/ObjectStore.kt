package com.agit.app.vcs

import com.agit.app.util.Crypto
import java.io.File

/**
 * 内容寻址对象库：blob 以 SHA-256 命名存储在 <repo>/.agit/objects/xx/yyyy。
 */
object ObjectStore {

    private fun path(repoRoot: File, hash: String): File {
        require(hash.length >= 3) { "blob hash too short" }
        return File(repoRoot, ".agit/objects/${hash.substring(0, 2)}/${hash.substring(2)}")
    }

    fun writeBlob(repoRoot: File, data: ByteArray): String {
        val hash = Crypto.sha256Hex(data)
        val f = path(repoRoot, hash)
        f.parentFile?.mkdirs()
        f.writeBytes(data)
        return hash
    }

    fun writeBlob(repoRoot: File, text: String): String =
        writeBlob(repoRoot, text.toByteArray(Charsets.UTF_8))

    fun readBlob(repoRoot: File, hash: String): ByteArray? {
        val f = path(repoRoot, hash)
        return if (f.exists()) f.readBytes() else null
    }

    fun readBlobText(repoRoot: File, hash: String): String? {
        val bytes = readBlob(repoRoot, hash) ?: return null
        val text = bytes.toString(Charsets.UTF_8)
        return if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text
    }

    fun exists(repoRoot: File, hash: String): Boolean = path(repoRoot, hash).exists()
}
