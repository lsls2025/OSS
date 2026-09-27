package com.agit.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * 领域模型（与 SQLite 表结构对应）。
 */

enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED, UNCHANGED }

data class User(
    val id: String = "",
    val username: String = "",
    val displayName: String = "",
    val avatar: String = "",
    val createdAt: Long = 0,
    val token: String = ""
) {
    fun displayNameOrUser(): String = if (displayName.isBlank()) username else displayName
    fun initial(): Char = displayNameOrUser().firstOrNull()?.uppercaseChar() ?: '#'
    fun gitEmail(): String = "${username}@agit.local"
}

data class Repo(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val isPrivate: Boolean = false,
    val defaultBranch: String = "main",
    val remoteRepoId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

data class BranchEntity(
    val repoId: String,
    val name: String,
    val headCommit: String,
    val isDefault: Boolean
)

data class CommitRecord(
    val id: String,
    val repoId: String,
    val branch: String,
    val treeId: String,
    val parentId: String,
    val authorId: String,
    val authorName: String,
    val message: String,
    val timestamp: Long,
    val additions: Int,
    val deletions: Int,
    val filesChanged: Int
) {
    val shortId: String get() = if (id.length >= 7) id.substring(0, 7) else id
}

data class TreeEntryRecord(
    val commitId: String,
    val path: String,
    val blobHash: String,
    val size: Long,
    val kind: String
)

data class FileChange(
    val path: String,
    val kind: ChangeKind,
    val oldHash: String = "",
    val newHash: String = "",
    val size: Long = 0,
    val isBinary: Boolean = false,
    val staged: Boolean = true
)

enum class DiffLineKind { CONTEXT, ADD, REMOVE }

data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldNumber: Int,
    val newNumber: Int
)

data class FileDiff(
    val path: String,
    val kind: ChangeKind,
    val isBinary: Boolean,
    val lines: MutableList<DiffLine> = mutableListOf(),
    var additions: Int = 0,
    var deletions: Int = 0
)

data class CommitDiff(
    val commit: CommitRecord,
    val files: MutableList<FileDiff> = mutableListOf(),
    var additions: Int = 0,
    var deletions: Int = 0
) {
    val filesChanged: Int get() = files.size
}

data class BranchInfo(
    val name: String,
    val headCommit: String,
    val isDefault: Boolean,
    var commitCount: Int = 0
)

data class RepoStatus(
    val branch: String,
    val changes: List<FileChange>
) {
    val hasChanges: Boolean get() = changes.isNotEmpty()
    val additions: Int get() = changes.count { it.kind == ChangeKind.ADDED }
    val deletions: Int get() = changes.count { it.kind == ChangeKind.DELETED }
    val modifications: Int get() = changes.count { it.kind == ChangeKind.MODIFIED }
}

data class CodeSearchHit(
    val path: String,
    val lineNumber: Int,
    val line: String
)

// ============== 远程仓库 / 连接 / 拉黑 ==============

data class RemoteUserLite(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("username") val username: String = "",
    @SerializedName("avatar") val avatar: String = ""
) {
    fun initial(): Char = username.firstOrNull()?.uppercaseChar() ?: '#'
}

data class RemoteRepo(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("is_private") val isPrivate: Boolean = false,
    @SerializedName("owner_id") val ownerId: Int = 0,
    @SerializedName("allow_connect") val allowConnect: Boolean = true,
    @SerializedName("role") val role: String? = null
)

data class Connection(
    @SerializedName("user") val user: RemoteUserLite = RemoteUserLite(),
    @SerializedName("created_at") val createdAt: Long = 0,
    @SerializedName("permission") val permission: String = "read"
)

data class Connector(
    @SerializedName("repo_id") val repoId: String = "",
    @SerializedName("repo_name") val repoName: String = "",
    @SerializedName("permission") val permission: String = "read",
    @SerializedName("user") val user: RemoteUserLite = RemoteUserLite()
)

data class BlockUser(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("username") val username: String = "",
    @SerializedName("avatar") val avatar: String = ""
)

data class ReposResponse(@SerializedName("repos") val repos: List<RemoteRepo> = emptyList())
data class ConnectionsResponse(@SerializedName("connections") val connections: List<Connection> = emptyList())
data class ConnectorsResponse(@SerializedName("connectors") val connectors: List<Connector> = emptyList())
data class BlocksResponse(@SerializedName("blocks") val blocks: List<BlockUser> = emptyList())
data class MessageResponse(
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)

data class VersionResponse(
    @SerializedName("version") val version: String = "",
    @SerializedName("min_version") val minVersion: String = ""
)

data class PublicRepoInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String = "",
    @SerializedName("owner_id") val ownerId: Int = 0,
    @SerializedName("owner_username") val ownerUsername: String = "",
    @SerializedName("is_private") val isPrivate: Boolean = false,
    @SerializedName("allow_connect") val allowConnect: Boolean = true
)

data class RepoSettings(
    @SerializedName("allow_connect") val allowConnect: Boolean = true,
    @SerializedName("connect_password") val connectPassword: String = ""
)

data class RepoUpdatePayload(
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("is_private") val isPrivate: Boolean? = null,
    @SerializedName("allow_connect") val allowConnect: Boolean? = null,
    @SerializedName("connect_password") val connectPassword: String? = null
)
