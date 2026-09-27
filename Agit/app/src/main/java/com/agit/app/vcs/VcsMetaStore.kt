package com.agit.app.vcs

import com.agit.app.data.model.BranchEntity
import com.agit.app.data.model.CommitRecord
import com.agit.app.data.model.TreeEntryRecord

/**
 * 版本控制元数据持久化接口（commit / tree / branch 的结构化存储）。
 * 由 DbHelper(Android SQLite) 实现；单元测试可注入内存实现。
 */
interface VcsMetaStore {
    fun getDefaultBranch(repoId: String): String
    fun getHeadCommit(repoId: String, branch: String): String
    fun setHeadCommit(repoId: String, branch: String, commitId: String)

    fun getBranch(repoId: String, branch: String): BranchEntity?
    fun listBranches(repoId: String): List<BranchEntity>
    fun createBranch(repoId: String, name: String, headCommit: String, isDefault: Boolean)
    fun deleteBranch(repoId: String, name: String)

    fun insertCommit(c: CommitRecord)
    fun getCommitById(commitId: String): CommitRecord?
    fun listCommits(repoId: String, branch: String): List<CommitRecord>
    fun listAllCommits(repoId: String): List<CommitRecord>
    fun listCommitsForFile(repoId: String, path: String): List<CommitRecord>

    fun insertTreeEntries(entries: List<TreeEntryRecord>)
    fun listTreeEntries(commitId: String): List<TreeEntryRecord>
    fun getTreeEntry(commitId: String, path: String): TreeEntryRecord?
}
