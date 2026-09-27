package com.agit.app.data.remote

import com.agit.app.data.model.BlocksResponse
import com.agit.app.data.model.ConnectionsResponse
import com.agit.app.data.model.ConnectorsResponse
import com.agit.app.data.model.MessageResponse
import com.agit.app.data.model.BlockUser
import com.agit.app.data.model.Connection
import com.agit.app.data.model.Connector
import com.agit.app.data.model.ReposResponse
import com.agit.app.data.model.RemoteRepo
import com.agit.app.data.model.VersionResponse
import com.agit.app.data.model.PublicRepoInfo
import com.agit.app.data.model.RepoSettings
import com.agit.app.data.model.RepoUpdatePayload
import com.google.gson.Gson
import retrofit2.Response

class RepoRepository(private val api: RepoApi) {

    private fun <T> Response<T>.unwrap(): T {
        if (isSuccessful) return body() ?: throw IllegalStateException("空响应(${code()})")
        val raw = try { errorBody()?.string() } catch (_: Exception) { null }
        val msg = raw?.let { try { Gson().fromJson(it, MessageResponse::class.java)?.error
            ?: Gson().fromJson(it, MessageResponse::class.java)?.message
        } catch (_: Exception) { null } }
        throw IllegalStateException(msg ?: "请求失败(${code()})")
    }

    suspend fun createRemoteRepo(name: String, description: String, isPrivate: Boolean): String {
        val resp = api.createRemoteRepo(
            mapOf("name" to name, "description" to description, "is_private" to isPrivate)
        )
        if (resp.isSuccessful) return resp.body()?.id ?: throw IllegalStateException("空响应")
        val raw = try { resp.errorBody()?.string() } catch (_: Exception) { null }
        val msg = raw?.let { try { Gson().fromJson(it, MessageResponse::class.java)?.error
            ?: Gson().fromJson(it, MessageResponse::class.java)?.message
        } catch (_: Exception) { null } }
        throw IllegalStateException(msg ?: "发布失败(${resp.code()})")
    }

    suspend fun listMyRepos(): List<RemoteRepo> = runCatching { api.listMyRepos().unwrap().repos }
        .getOrElse { emptyList() }

    suspend fun searchPublic(q: String, page: Int = 0, size: Int = 20): List<RemoteRepo> =
        api.searchPublic(q, page, size).unwrap().repos

    suspend fun getVersion(): VersionResponse = api.getVersion().unwrap()

    suspend fun getRepoInfo(rid: String): PublicRepoInfo = api.getRepo(rid).unwrap()

    suspend fun listAllPublic(): List<RemoteRepo> = runCatching { api.searchPublic("").unwrap().repos }
        .getOrElse { emptyList() }

    suspend fun connect(rid: String, password: String? = null) {
        api.connect(rid, if (password.isNullOrEmpty()) emptyMap() else mapOf("password" to password)).unwrap()
    }

    suspend fun updateRepo(rid: String, payload: RepoUpdatePayload): MessageResponse =
        api.updateRepo(rid, payload).unwrap()

    suspend fun getRepoSettings(rid: String): RepoSettings = api.getRepoSettings(rid).unwrap()

    suspend fun listConnections(rid: String): List<Connection> = runCatching { api.listConnections(rid).unwrap().connections }
        .getOrElse { emptyList() }

    suspend fun removeConnection(rid: String, uid: Int) { api.removeConnection(rid, uid).unwrap() }

    suspend fun setPermission(rid: String, uid: Int, permission: String) {
        api.setPermission(rid, uid, mapOf("permission" to permission)).unwrap()
    }

    suspend fun block(rid: String, uid: Int) { api.block(rid, mapOf("user_id" to uid)).unwrap() }
    suspend fun myConnectors(): List<Connector> = runCatching { api.myConnectors().unwrap().connectors }
        .getOrElse { emptyList() }
    suspend fun listBlocks(): List<BlockUser> = runCatching { api.listBlocks().unwrap().blocks }
        .getOrElse { emptyList() }
    suspend fun unblock(uid: Int) { api.unblock(mapOf("user_id" to uid)).unwrap() }
}
