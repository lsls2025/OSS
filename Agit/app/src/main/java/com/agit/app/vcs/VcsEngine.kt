package com.agit.app.vcs

import com.agit.app.data.model.BranchInfo
import com.agit.app.data.model.ChangeKind
import com.agit.app.data.model.CodeSearchHit
import com.agit.app.data.model.CommitDiff
import com.agit.app.data.model.CommitRecord
import com.agit.app.data.model.DiffLineKind
import com.agit.app.data.model.FileChange
import com.agit.app.data.model.RepoStatus
import com.agit.app.data.model.TreeEntryRecord
import com.agit.app.util.Crypto
import java.io.File
import java.util.Calendar

/**
 * 自研内容寻址版本控制引擎（不依赖外部 git）。
 * 目录约定：<repoRoot>/.agit（对象库 + 分支元数据）与 <repoRoot>/workspace（工作区）。
 */
class VcsEngine(private val repoRoot: File, private val store: VcsMetaStore) {

    private val agitDir get() = File(repoRoot, ".agit")
    private val workspace get() = File(repoRoot, "workspace")

    // ---------------- 初始化 ----------------
    fun initRepo(
        repoId: String,
        ownerId: String,
        ownerName: String,
        repoName: String,
        description: String,
        initMessage: String = "Initial commit"
    ): CommitRecord {
        agitDir.mkdirs()
        workspace.mkdirs()
        store.createBranch(repoId, "main", "", true)

        val year = Calendar.getInstance().get(Calendar.YEAR)
        val readme = buildString {
            appendLine("# $repoName")
            appendLine()
            if (description.isNotBlank()) {
                appendLine(description)
                appendLine()
            }
            appendLine("> 由 Agit 创建的本地代码仓库。")
            appendLine()
            appendLine("## 快速开始")
            appendLine()
            appendLine("1. 在「代码」页新建或导入文件；")
            appendLine("2. 在「提交」页填写提交信息并保存；")
            appendLine("3. 在「记录」页查看全部修改与提交历史。")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("Copyright (c) $year $ownerName")
        }
        workspace.resolve("README.md").writeText(readme, Charsets.UTF_8)
        return commit(repoId, ownerId, ownerName, initMessage, null)
            ?: throw IllegalStateException("初始化提交失败")
    }

    // ---------------- 工作区扫描 ----------------
    private fun scanWorkspace(): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        workspace.listFiles()?.forEach { top ->
            if (top.name == ".agit") return@forEach
            if (top.isDirectory) walk(top, top.name, result)
            else result[top.name] = top.readBytes()
        }
        return result
    }

    private fun walk(dir: File, prefix: String, out: MutableMap<String, ByteArray>) {
        dir.listFiles()?.forEach { child ->
            val rel = "$prefix/${child.name}"
            if (child.isDirectory) walk(child, rel, out)
            else out[rel] = child.readBytes()
        }
    }

    private fun clearWorkspace() {
        workspace.listFiles()?.forEach { f ->
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
    }

    // ---------------- 状态 ----------------
    fun status(repoId: String): RepoStatus {
        val branch = store.getDefaultBranch(repoId)
        val head = store.getHeadCommit(repoId, branch)
        val parentTree = if (head.isNotEmpty())
            store.listTreeEntries(head).associateBy { it.path } else emptyMap()
        val working = scanWorkspace()
        val changes = computeChanges(parentTree, working).sortedBy { it.path }
        return RepoStatus(branch, changes)
    }

    private fun computeChanges(
        parentTree: Map<String, TreeEntryRecord>,
        working: Map<String, ByteArray>
    ): List<FileChange> {
        val changes = mutableListOf<FileChange>()
        for ((path, bytes) in working) {
            val newHash = Crypto.sha256Hex(bytes)
            val old = parentTree[path]
            if (old == null) {
                changes.add(FileChange(path, ChangeKind.ADDED, newHash = newHash,
                    size = bytes.size.toLong(), isBinary = DiffEngine.isBinary(bytes)))
            } else if (old.blobHash != newHash) {
                changes.add(FileChange(path, ChangeKind.MODIFIED, oldHash = old.blobHash,
                    newHash = newHash, size = bytes.size.toLong(),
                    isBinary = DiffEngine.isBinary(bytes)))
            }
        }
        for ((path, entry) in parentTree) {
            if (!working.containsKey(path)) {
                changes.add(FileChange(path, ChangeKind.DELETED, oldHash = entry.blobHash, size = entry.size))
            }
        }
        return changes
    }

    // ---------------- 目录 ----------------
    fun treeAtHead(repoId: String): List<TreeEntryRecord> {
        val head = store.getHeadCommit(repoId, store.getDefaultBranch(repoId))
        return if (head.isEmpty()) emptyList() else store.listTreeEntries(head)
    }

    /** 将工作区整体恢复为某次提交的内容（会覆盖未提交更改）。 */
    fun restoreWorkspaceToCommit(repoId: String, commitId: String): Boolean {
        if (store.getCommitById(commitId) == null) return false
        val entries = store.listTreeEntries(commitId)
        clearWorkspace()
        for (e in entries) {
            val bytes = ObjectStore.readBlob(repoRoot, e.blobHash) ?: continue
            writeFileBytes(e.path, bytes)
        }
        return true
    }

    // ---------------- 提交 ----------------
    /** selectedPaths 为 null 或空表示提交全部变更。无可提交变更时返回 null。 */
    fun commit(
        repoId: String,
        authorId: String,
        authorName: String,
        message: String,
        selectedPaths: Set<String>?
    ): CommitRecord? {
        val branch = store.getDefaultBranch(repoId)
        val parentId = store.getHeadCommit(repoId, branch)
        val allChanges = status(repoId).changes
        val toApply = if (selectedPaths.isNullOrEmpty()) allChanges
        else allChanges.filter { it.path in selectedPaths }
        if (toApply.isEmpty()) return null

        val parentEntries = if (parentId.isNotEmpty())
            store.listTreeEntries(parentId).associateBy { it.path } else emptyMap()
        val working = scanWorkspace()
        val newEntries = parentEntries.toMutableMap()
        var additions = 0
        var deletions = 0
        var filesChanged = 0

        for (ch in toApply) {
            val bytes = working[ch.path] ?: continue
            when (ch.kind) {
                ChangeKind.ADDED, ChangeKind.MODIFIED -> {
                    val hash = ObjectStore.writeBlob(repoRoot, bytes)
                    newEntries[ch.path] = TreeEntryRecord("", ch.path, hash, bytes.size.toLong(), "file")
                    if (ch.kind == ChangeKind.ADDED) {
                        additions += lineCountText(bytes)
                        filesChanged++
                    } else {
                        val oldBytes = store.getTreeEntry(parentId, ch.path)
                            ?.let { ObjectStore.readBlob(repoRoot, it.blobHash) }
                        val oldLines = oldBytes?.let { lineCountText(it) } ?: 0
                        val newLines = lineCountText(bytes)
                        additions += maxOf(0, newLines - oldLines)
                        deletions += maxOf(0, oldLines - newLines)
                        filesChanged++
                    }
                }
                ChangeKind.DELETED -> {
                    val oldBytes = store.getTreeEntry(parentId, ch.path)
                        ?.let { ObjectStore.readBlob(repoRoot, it.blobHash) }
                    deletions += oldBytes?.let { lineCountText(it) } ?: 0
                    newEntries.remove(ch.path)
                    filesChanged++
                }
                else -> { /* unchanged */ }
            }
        }

        val sorted = newEntries.values.sortedBy { it.path }
        val treePayload = sorted.joinToString("\n") { "${it.path}\t${it.blobHash}\t${it.size}" }
        val treeId = Crypto.sha256Hex(treePayload)
        val ts = System.currentTimeMillis()
        val commitId = Crypto.stableHash(repoId, treeId, parentId, authorId, message, ts.toString(), branch)
        val entries = sorted.map { it.copy(commitId = commitId) }
        store.insertTreeEntries(entries)

        val rec = CommitRecord(
            id = commitId, repoId = repoId, branch = branch, treeId = treeId,
            parentId = parentId, authorId = authorId, authorName = authorName,
            message = message, timestamp = ts, additions = additions,
            deletions = deletions, filesChanged = filesChanged
        )
        store.insertCommit(rec)
        store.setHeadCommit(repoId, branch, commitId)
        return rec
    }

    // ---------------- Diff ----------------
    fun diff(commitId: String): CommitDiff? {
        val commit = store.getCommitById(commitId) ?: return null
        val parentEntries = if (commit.parentId.isNotEmpty())
            store.listTreeEntries(commit.parentId).associateBy { it.path } else emptyMap()
        val curEntries = store.listTreeEntries(commit.id).associateBy { it.path }
        val paths = (parentEntries.keys + curEntries.keys).sorted().toSet()

        val result = CommitDiff(commit = commit)
        for (path in paths) {
            val oldE = parentEntries[path]
            val newE = curEntries[path]
            val kind = when {
                newE == null -> ChangeKind.DELETED
                oldE == null -> ChangeKind.ADDED
                oldE.blobHash != newE.blobHash -> ChangeKind.MODIFIED
                else -> continue
            }
            val oldBytes = oldE?.let { ObjectStore.readBlob(repoRoot, it.blobHash) }
            val newBytes = newE?.let { ObjectStore.readBlob(repoRoot, it.blobHash) }
            val isBinary = (oldBytes != null && DiffEngine.isBinary(oldBytes)) ||
                (newBytes != null && DiffEngine.isBinary(newBytes))
            val fd = com.agit.app.data.model.FileDiff(path = path, kind = kind, isBinary = isBinary)
            if (isBinary) {
                val oldLines = oldBytes?.let { lineCountText(it) } ?: 0
                val newLines = newBytes?.let { lineCountText(it) } ?: 0
                when (kind) {
                    ChangeKind.ADDED -> fd.additions = newLines
                    ChangeKind.DELETED -> fd.deletions = oldLines
                    else -> { fd.additions = newLines; fd.deletions = oldLines }
                }
            } else {
                val oldText = oldBytes?.toString(Charsets.UTF_8)?.normalizeEol()?.lines() ?: emptyList()
                val newText = newBytes?.toString(Charsets.UTF_8)?.normalizeEol()?.lines() ?: emptyList()
                val lines = DiffEngine.diffLines(oldText, newText)
                fd.lines.addAll(lines)
                for (l in lines) {
                    if (l.kind == DiffLineKind.ADD) fd.additions++
                    else if (l.kind == DiffLineKind.REMOVE) fd.deletions++
                }
            }
            result.files.add(fd)
            result.additions += fd.additions
            result.deletions += fd.deletions
        }
        return result
    }

    // ---------------- 历史 ----------------
    fun log(repoId: String, branch: String): List<CommitRecord> = chainFrom(store.getHeadCommit(repoId, branch))

    fun logAll(repoId: String): List<CommitRecord> {
        val seen = mutableSetOf<String>()
        val all = mutableListOf<CommitRecord>()
        for (b in store.listBranches(repoId)) {
            var cur = b.headCommit
            while (cur.isNotEmpty() && !seen.contains(cur)) {
                val c = store.getCommitById(cur) ?: break
                seen.add(cur)
                all.add(c)
                cur = c.parentId
            }
        }
        all.sortByDescending { it.timestamp }
        return all
    }

    fun fileHistory(repoId: String, path: String): List<CommitRecord> =
        store.listCommitsForFile(repoId, path)

    private fun chainFrom(headId: String): MutableList<CommitRecord> {
        val out = mutableListOf<CommitRecord>()
        val seen = mutableSetOf<String>()
        var cur = headId
        while (cur.isNotEmpty() && !seen.contains(cur)) {
            val c = store.getCommitById(cur) ?: break
            out.add(c)
            seen.add(cur)
            cur = c.parentId
        }
        return out
    }

    // ---------------- 分支 ----------------
    fun branches(repoId: String): List<BranchInfo> {
        return store.listBranches(repoId).map { b ->
            val count = chainFrom(b.headCommit).size
            BranchInfo(b.name, b.headCommit, b.isDefault, count)
        }
    }

    fun createBranch(repoId: String, name: String, fromCommitId: String? = null): Boolean {
        val from = fromCommitId ?: store.getHeadCommit(repoId, store.getDefaultBranch(repoId))
        if (store.getBranch(repoId, name) != null) return false
        store.createBranch(repoId, name, from, false)
        return true
    }

    fun deleteBranch(repoId: String, name: String): Boolean {
        val b = store.getBranch(repoId, name) ?: return false
        if (b.isDefault) return false
        store.deleteBranch(repoId, name)
        return true
    }

    /** 将工作区重置为目标分支的最新树（会丢弃未提交更改）。 */
    fun checkout(repoId: String, branch: String): Boolean {
        val commitId = store.getHeadCommit(repoId, branch)
        if (commitId.isEmpty()) return false
        val entries = store.listTreeEntries(commitId)
        clearWorkspace()
        for (e in entries) {
            val bytes = ObjectStore.readBlob(repoRoot, e.blobHash) ?: continue
            val f = workspace.resolve(e.path.replace('/', File.separatorChar))
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
        return true
    }

    // ---------------- 文件读写 ----------------
    fun writeFile(relPath: String, content: String) {
        val f = workspace.resolve(relPath.replace('/', File.separatorChar))
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
    }

    fun writeFileBytes(relPath: String, bytes: ByteArray) {
        val f = workspace.resolve(relPath.replace('/', File.separatorChar))
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    fun deleteFile(relPath: String): Boolean =
        workspace.resolve(relPath.replace('/', File.separatorChar)).delete()

    fun importFiles(files: Map<String, ByteArray>) {
        for ((rel, bytes) in files) writeFileBytes(rel, bytes)
    }

    fun readWorkspaceFile(relPath: String): String? {
        val f = workspace.resolve(relPath.replace('/', File.separatorChar))
        return if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    fun getFileContentAtCommit(commitId: String, path: String): String? {
        val e = store.getTreeEntry(commitId, path) ?: return null
        return ObjectStore.readBlobText(repoRoot, e.blobHash)
    }

    fun restoreFileFromCommit(commitId: String, path: String): Boolean {
        val e = store.getTreeEntry(commitId, path) ?: return false
        val bytes = ObjectStore.readBlob(repoRoot, e.blobHash) ?: return false
        writeFileBytes(path, bytes)
        return true
    }

    // ---------------- 搜索 ----------------
    fun searchCode(repoId: String, query: String): List<CodeSearchHit> {
        val branch = store.getDefaultBranch(repoId)
        val head = store.getHeadCommit(repoId, branch)
        if (head.isEmpty() || query.isBlank()) return emptyList()
        val entries = store.listTreeEntries(head)
        val hits = mutableListOf<CodeSearchHit>()
        val q = query.lowercase()
        for (e in entries) {
            if (hits.size > 800) break
            if (e.path.lowercase().contains(q)) {
                val bytes = ObjectStore.readBlob(repoRoot, e.blobHash)
                val first = if (bytes != null && !DiffEngine.isBinary(bytes))
                    bytes.toString(Charsets.UTF_8).normalizeEol().lines().firstOrNull() ?: "" else ""
                hits.add(CodeSearchHit(e.path, 1, first))
                continue
            }
            val bytes = ObjectStore.readBlob(repoRoot, e.blobHash) ?: continue
            if (DiffEngine.isBinary(bytes)) continue
            val lines = bytes.toString(Charsets.UTF_8).normalizeEol().lines()
            lines.forEachIndexed { i, line ->
                if (line.lowercase().contains(q)) hits.add(CodeSearchHit(e.path, i + 1, line))
            }
        }
        return hits
    }

    // ---------------- 工具 ----------------
    private fun lineCountText(bytes: ByteArray): Int {
        if (DiffEngine.isBinary(bytes)) return 0
        val text = bytes.toString(Charsets.UTF_8).normalizeEol()
        if (text.isEmpty()) return 0
        return text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
    }

    private fun String.normalizeEol(): String = replace("\r\n", "\n").replace("\r", "\n")
}
