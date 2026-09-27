package com.agit.app.data.remote

import com.agit.app.data.model.BlocksResponse
import com.agit.app.data.model.ConnectionsResponse
import com.agit.app.data.model.ConnectorsResponse
import com.agit.app.data.model.MessageResponse
import com.agit.app.data.model.ReposResponse
import com.agit.app.data.model.RemoteRepo
import com.agit.app.data.model.VersionResponse
import com.agit.app.data.model.PublicRepoInfo
import com.agit.app.data.model.RepoSettings
import com.agit.app.data.model.RepoUpdatePayload
import retrofit2.Response
import retrofit2.http.Path
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface RepoApi {
    @POST("backend/repos")
    suspend fun createRemoteRepo(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<RemoteRepo>

    @GET("backend/repos")
    suspend fun listMyRepos(): Response<ReposResponse>

    @GET("backend/repos/search")
    suspend fun searchPublic(
        @Query("q") q: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<ReposResponse>

    @GET("backend/version")
    suspend fun getVersion(): Response<VersionResponse>

    @GET("backend/repos/{rid}")
    suspend fun getRepo(@Path("rid") rid: String): Response<PublicRepoInfo>

    @POST("backend/repos/{rid}/connect")
    suspend fun connect(@Path("rid") rid: String, @Body body: Map<String, @JvmSuppressWildcards Any>): Response<MessageResponse>

    @PUT("backend/repos/{rid}")
    suspend fun updateRepo(@Path("rid") rid: String, @Body body: RepoUpdatePayload): Response<MessageResponse>

    @GET("backend/repos/{rid}/settings")
    suspend fun getRepoSettings(@Path("rid") rid: String): Response<RepoSettings>

    @GET("backend/repos/{rid}/connections")
    suspend fun listConnections(@Path("rid") rid: String): Response<ConnectionsResponse>

    @DELETE("backend/repos/{rid}/connections/{uid}")
    suspend fun removeConnection(@Path("rid") rid: String, @Path("uid") uid: Int): Response<MessageResponse>

    @POST("backend/repos/{rid}/connections/{uid}/permission")
    suspend fun setPermission(
        @Path("rid") rid: String,
        @Path("uid") uid: Int,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<MessageResponse>

    @POST("backend/repos/{rid}/block")
    suspend fun block(@Path("rid") rid: String, @Body body: Map<String, @JvmSuppressWildcards Any>): Response<MessageResponse>

    @GET("backend/my-connectors")
    suspend fun myConnectors(): Response<ConnectorsResponse>

    @GET("backend/blocks")
    suspend fun listBlocks(): Response<BlocksResponse>

    @HTTP(method = "DELETE", path = "backend/blocks", hasBody = true)
    suspend fun unblock(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<MessageResponse>
}
