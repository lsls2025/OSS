package com.agit.app.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.agit.app.data.model.BranchEntity
import com.agit.app.data.model.CommitRecord
import com.agit.app.data.model.Repo
import com.agit.app.data.model.TreeEntryRecord
import com.agit.app.data.model.User
import com.agit.app.vcs.VcsMetaStore

class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), VcsMetaStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE users (
                id TEXT PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                displayName TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL,
                token TEXT NOT NULL DEFAULT ''
            )"""
        )
        db.execSQL(
            """CREATE TABLE repos (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                ownerId TEXT NOT NULL,
                ownerName TEXT NOT NULL DEFAULT '',
                isPrivate INTEGER NOT NULL DEFAULT 0,
                defaultBranch TEXT NOT NULL DEFAULT 'main',
                remote_repo_id TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE branches (
                repoId TEXT NOT NULL,
                name TEXT NOT NULL,
                headCommit TEXT NOT NULL DEFAULT '',
                isDefault INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (repoId, name)
            )"""
        )
        db.execSQL(
            """CREATE TABLE commits (
                id TEXT PRIMARY KEY,
                repoId TEXT NOT NULL,
                branch TEXT NOT NULL,
                treeId TEXT NOT NULL,
                parentId TEXT NOT NULL DEFAULT '',
                authorId TEXT NOT NULL,
                authorName TEXT NOT NULL,
                message TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                additions INTEGER NOT NULL DEFAULT 0,
                deletions INTEGER NOT NULL DEFAULT 0,
                filesChanged INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE tree_entries (
                commitId TEXT NOT NULL,
                path TEXT NOT NULL,
                blobHash TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                kind TEXT NOT NULL DEFAULT 'file',
                PRIMARY KEY (commitId, path)
            )"""
        )
        db.execSQL(
            """CREATE TABLE stars (
                repoId TEXT NOT NULL,
                userId TEXT NOT NULL,
                PRIMARY KEY (repoId, userId)
            )"""
        )
        db.execSQL("CREATE INDEX idx_commits_repo ON commits(repoId, branch, timestamp)")
        db.execSQL("CREATE INDEX idx_tree_commit ON tree_entries(commitId)")
        db.execSQL("CREATE INDEX idx_tree_path ON tree_entries(path)")
        db.execSQL("CREATE INDEX idx_stars_user ON stars(userId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS stars")
        db.execSQL("DROP TABLE IF EXISTS tree_entries")
        db.execSQL("DROP TABLE IF EXISTS commits")
        db.execSQL("DROP TABLE IF EXISTS branches")
        db.execSQL("DROP TABLE IF EXISTS repos")
        db.execSQL("DROP TABLE IF EXISTS users")
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            db.execSQL("ALTER TABLE repos ADD COLUMN remote_repo_id TEXT NOT NULL DEFAULT ''")
        } catch (_: Exception) {
        }
    }

    // ============== 用户 ==============
    fun insertUser(user: User) {
        val cv = ContentValues().apply {
            put("id", user.id)
            put("username", user.username)
            put("displayName", user.displayName)
            put("createdAt", user.createdAt)
            put("token", user.token)
        }
        writableDatabase.insert("users", null, cv)
    }

    fun getUserById(id: String): User? {
        val c = readableDatabase.rawQuery("SELECT * FROM users WHERE id=?", arrayOf(id))
        return c.use { if (it.moveToFirst()) cursorToUser(it) else null }
    }

    fun updateDisplayName(id: String, displayName: String) {
        val cv = ContentValues().apply { put("displayName", displayName) }
        writableDatabase.update("users", cv, "id=?", arrayOf(id))
    }

    private fun cursorToUser(c: Cursor): User = User(
        id = str(c, "id"),
        username = str(c, "username"),
        displayName = str(c, "displayName"),
        createdAt = lng(c, "createdAt"),
        token = str(c, "token")
    )

    // ============== 仓库 ==============
    fun insertRepo(repo: Repo) {
        val cv = ContentValues().apply {
            put("id", repo.id)
            put("name", repo.name)
            put("description", repo.description)
            put("ownerId", repo.ownerId)
            put("ownerName", repo.ownerName)
            put("isPrivate", if (repo.isPrivate) 1 else 0)
            put("defaultBranch", repo.defaultBranch)
            put("remote_repo_id", repo.remoteRepoId)
            put("createdAt", repo.createdAt)
            put("updatedAt", repo.updatedAt)
        }
        writableDatabase.insert("repos", null, cv)
    }

    fun getRepo(id: String): Repo? {
        val c = readableDatabase.rawQuery("SELECT * FROM repos WHERE id=?", arrayOf(id))
        return c.use { if (it.moveToFirst()) cursorToRepo(it) else null }
    }

    fun repoNameExists(name: String, ownerId: String, exceptId: String = ""): Boolean {
        val c = readableDatabase.rawQuery(
            "SELECT id FROM repos WHERE name=? AND ownerId=? AND id<>?",
            arrayOf(name, ownerId, exceptId)
        )
        return c.use { it.moveToFirst() }
    }

    fun updateRepoMeta(id: String, name: String, description: String, isPrivate: Boolean) {
        val cv = ContentValues().apply {
            put("name", name)
            put("description", description)
            put("isPrivate", if (isPrivate) 1 else 0)
        }
        writableDatabase.update("repos", cv, "id=?", arrayOf(id))
    }

    fun updateRemoteRepoId(id: String, remoteRepoId: String) {
        val cv = ContentValues().apply { put("remote_repo_id", remoteRepoId) }
        writableDatabase.update("repos", cv, "id=?", arrayOf(id))
    }

    fun updateDefaultBranch(id: String, branchName: String) {
        val cv = ContentValues().apply { put("defaultBranch", branchName) }
        writableDatabase.update("repos", cv, "id=?", arrayOf(id))
    }

    fun touchRepo(id: String) {
        val cv = ContentValues().apply { put("updatedAt", System.currentTimeMillis()) }
        writableDatabase.update("repos", cv, "id=?", arrayOf(id))
    }

    fun deleteRepo(id: String) {
        writableDatabase.delete("repos", "id=?", arrayOf(id))
        writableDatabase.delete("branches", "repoId=?", arrayOf(id))
        writableDatabase.delete("commits", "repoId=?", arrayOf(id))
        writableDatabase.delete("stars", "repoId=?", arrayOf(id))
    }

    /** 可访问仓库：自己的 + 别人公开。 */
    fun listRepos(userId: String): List<Repo> {
        val list = mutableListOf<Repo>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM repos WHERE ownerId=? OR isPrivate=0 ORDER BY updatedAt DESC",
            arrayOf(userId)
        )
        c.use { while (it.moveToNext()) list.add(cursorToRepo(it)) }
        return list
    }

    fun listMyRepos(userId: String): List<Repo> {
        val list = mutableListOf<Repo>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM repos WHERE ownerId=? ORDER BY updatedAt DESC",
            arrayOf(userId)
        )
        c.use { while (it.moveToNext()) list.add(cursorToRepo(it)) }
        return list
    }

    fun listStarredRepos(userId: String): List<Repo> {
        val list = mutableListOf<Repo>()
        val c = readableDatabase.rawQuery(
            """SELECT r.* FROM repos r JOIN stars s ON r.id=s.repoId
               WHERE s.userId=? ORDER BY r.updatedAt DESC""",
            arrayOf(userId)
        )
        c.use { while (it.moveToNext()) list.add(cursorToRepo(it)) }
        return list
    }

    fun isStarred(repoId: String, userId: String): Boolean {
        val c = readableDatabase.rawQuery(
            "SELECT repoId FROM stars WHERE repoId=? AND userId=?",
            arrayOf(repoId, userId)
        )
        return c.use { it.moveToFirst() }
    }

    fun star(repoId: String, userId: String) {
        val cv = ContentValues().apply { put("repoId", repoId); put("userId", userId) }
        writableDatabase.insertWithOnConflict("stars", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun unstar(repoId: String, userId: String) {
        writableDatabase.delete("stars", "repoId=? AND userId=?", arrayOf(repoId, userId))
    }

    fun countCommits(repoId: String): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM commits WHERE repoId=?", arrayOf(repoId)
        )
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun cursorToRepo(c: Cursor): Repo = Repo(
        id = str(c, "id"),
        name = str(c, "name"),
        description = str(c, "description"),
        ownerId = str(c, "ownerId"),
        ownerName = str(c, "ownerName"),
        isPrivate = int(c, "isPrivate") != 0,
        defaultBranch = str(c, "defaultBranch", "main"),
        remoteRepoId = str(c, "remote_repo_id"),
        createdAt = lng(c, "createdAt"),
        updatedAt = lng(c, "updatedAt")
    )

    // ============== VcsMetaStore 实现 ==============
    override fun getDefaultBranch(repoId: String): String {
        val c = readableDatabase.rawQuery(
            "SELECT name FROM branches WHERE repoId=? AND isDefault=1", arrayOf(repoId)
        )
        return c.use { if (it.moveToFirst()) str(it, "name") else "main" }
    }

    override fun getHeadCommit(repoId: String, branch: String): String {
        return getBranch(repoId, branch)?.headCommit ?: ""
    }

    override fun setHeadCommit(repoId: String, branch: String, commitId: String) {
        if (getBranch(repoId, branch) == null) {
            val cv = ContentValues().apply {
                put("repoId", repoId); put("name", branch)
                put("headCommit", commitId); put("isDefault", 0)
            }
            writableDatabase.insert("branches", null, cv)
        } else {
            val cv = ContentValues().apply { put("headCommit", commitId) }
            writableDatabase.update("branches", cv, "repoId=? AND name=?", arrayOf(repoId, branch))
        }
    }

    override fun getBranch(repoId: String, branch: String): BranchEntity? {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM branches WHERE repoId=? AND name=?",
            arrayOf(repoId, branch)
        )
        return c.use { if (it.moveToFirst()) cursorToBranch(it) else null }
    }

    override fun listBranches(repoId: String): List<BranchEntity> {
        val list = mutableListOf<BranchEntity>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM branches WHERE repoId=? ORDER BY isDefault DESC, name ASC",
            arrayOf(repoId)
        )
        c.use { while (it.moveToNext()) list.add(cursorToBranch(it)) }
        return list
    }

    override fun createBranch(repoId: String, name: String, headCommit: String, isDefault: Boolean) {
        val cv = ContentValues().apply {
            put("repoId", repoId); put("name", name)
            put("headCommit", headCommit); put("isDefault", if (isDefault) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("branches", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun deleteBranch(repoId: String, name: String) {
        writableDatabase.delete("branches", "repoId=? AND name=?", arrayOf(repoId, name))
    }

    private fun cursorToBranch(c: Cursor): BranchEntity = BranchEntity(
        repoId = str(c, "repoId"),
        name = str(c, "name"),
        headCommit = str(c, "headCommit"),
        isDefault = int(c, "isDefault") != 0
    )

    override fun insertCommit(c: CommitRecord) {
        val cv = ContentValues().apply {
            put("id", c.id)
            put("repoId", c.repoId)
            put("branch", c.branch)
            put("treeId", c.treeId)
            put("parentId", c.parentId)
            put("authorId", c.authorId)
            put("authorName", c.authorName)
            put("message", c.message)
            put("timestamp", c.timestamp)
            put("additions", c.additions)
            put("deletions", c.deletions)
            put("filesChanged", c.filesChanged)
        }
        writableDatabase.insert("commits", null, cv)
    }

    override fun getCommitById(commitId: String): CommitRecord? {
        val c = readableDatabase.rawQuery("SELECT * FROM commits WHERE id=?", arrayOf(commitId))
        return c.use { if (it.moveToFirst()) cursorToCommit(it) else null }
    }

    override fun listCommits(repoId: String, branch: String): List<CommitRecord> {
        val list = mutableListOf<CommitRecord>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM commits WHERE repoId=? AND branch=? ORDER BY timestamp DESC",
            arrayOf(repoId, branch)
        )
        c.use { while (it.moveToNext()) list.add(cursorToCommit(it)) }
        return list
    }

    override fun listAllCommits(repoId: String): List<CommitRecord> {
        val list = mutableListOf<CommitRecord>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM commits WHERE repoId=? ORDER BY timestamp DESC",
            arrayOf(repoId)
        )
        c.use { while (it.moveToNext()) list.add(cursorToCommit(it)) }
        return list
    }

    override fun listCommitsForFile(repoId: String, path: String): List<CommitRecord> {
        val list = mutableListOf<CommitRecord>()
        val c = readableDatabase.rawQuery(
            """SELECT c.* FROM commits c JOIN tree_entries t ON c.id=t.commitId
               WHERE c.repoId=? AND t.path=? GROUP BY c.id ORDER BY c.timestamp DESC""",
            arrayOf(repoId, path)
        )
        c.use { while (it.moveToNext()) list.add(cursorToCommit(it)) }
        return list
    }

    override fun insertTreeEntries(entries: List<TreeEntryRecord>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (e in entries) {
                val cv = ContentValues().apply {
                    put("commitId", e.commitId)
                    put("path", e.path)
                    put("blobHash", e.blobHash)
                    put("size", e.size)
                    put("kind", e.kind)
                }
                db.insert("tree_entries", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun listTreeEntries(commitId: String): List<TreeEntryRecord> {
        val list = mutableListOf<TreeEntryRecord>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM tree_entries WHERE commitId=? ORDER BY path ASC",
            arrayOf(commitId)
        )
        c.use { while (it.moveToNext()) list.add(cursorToTreeEntry(it)) }
        return list
    }

    override fun getTreeEntry(commitId: String, path: String): TreeEntryRecord? {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM tree_entries WHERE commitId=? AND path=?",
            arrayOf(commitId, path)
        )
        return c.use { if (it.moveToFirst()) cursorToTreeEntry(it) else null }
    }

    private fun cursorToCommit(c: Cursor): CommitRecord = CommitRecord(
        id = str(c, "id"),
        repoId = str(c, "repoId"),
        branch = str(c, "branch"),
        treeId = str(c, "treeId"),
        parentId = str(c, "parentId"),
        authorId = str(c, "authorId"),
        authorName = str(c, "authorName"),
        message = str(c, "message"),
        timestamp = lng(c, "timestamp"),
        additions = int(c, "additions"),
        deletions = int(c, "deletions"),
        filesChanged = int(c, "filesChanged")
    )

    private fun cursorToTreeEntry(c: Cursor): TreeEntryRecord = TreeEntryRecord(
        commitId = str(c, "commitId"),
        path = str(c, "path"),
        blobHash = str(c, "blobHash"),
        size = lng(c, "size"),
        kind = str(c, "kind", "file")
    )

    private fun str(c: Cursor, name: String, def: String = ""): String {
        val i = c.getColumnIndex(name)
        return if (i < 0) def else c.getString(i) ?: def
    }

    private fun lng(c: Cursor, name: String, def: Long = 0): Long {
        val i = c.getColumnIndex(name)
        return if (i < 0) def else c.getLong(i)
    }

    private fun int(c: Cursor, name: String, def: Int = 0): Int {
        val i = c.getColumnIndex(name)
        return if (i < 0) def else c.getInt(i)
    }

    companion object {
        private const val DB_NAME = "agit.db"
        private const val DB_VERSION = 2
    }
}
