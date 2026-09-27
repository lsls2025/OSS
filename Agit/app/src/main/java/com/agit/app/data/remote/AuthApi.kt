package com.agit.app.data.remote

import com.agit.app.data.model.MessageResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {
    @POST("backend/register")
    suspend fun register(@Body req: AuthRequest): Response<AuthResponse>

    @POST("backend/login")
    suspend fun login(@Body req: AuthRequest): Response<AuthResponse>

    @POST("backend/change-password")
    suspend fun changePassword(
        @Header("Authorization") token: String,
        @Body req: ChangePasswordRequest
    ): Response<MessageResponse>

    @GET("backend/me")
    suspend fun me(@Header("Authorization") token: String): Response<MeResponse>

    @POST("backend/avatar")
    suspend fun uploadAvatar(
        @Header("Authorization") token: String,
        @Body req: AvatarRequest
    ): Response<AvatarResponse>
}

data class AuthRequest(val username: String, val password: String)

data class ChangePasswordRequest(val old_password: String, val new_password: String)

data class AuthResponse(val token: String, val user: RemoteUser)

data class MeResponse(val user: RemoteUser)

data class RemoteUser(
    val id: Long,
    val username: String,
    val created_at: Long,
    val avatar: String = ""
)

data class AvatarRequest(val avatar: String)

data class AvatarResponse(val message: String?, val avatar: String?)
