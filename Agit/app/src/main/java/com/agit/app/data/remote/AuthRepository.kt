package com.agit.app.data.remote

import com.agit.app.data.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(private val api: AuthApi) {

    suspend fun register(username: String, password: String): Result<User> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.register(AuthRequest(username, password))
                if (resp.isSuccessful) {
                    resp.body()!!.toUser()
                } else {
                    throw Exception(resp.errorBody()?.string()?.take(120) ?: "注册失败")
                }
            }
        }

    suspend fun login(username: String, password: String): Result<User> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.login(AuthRequest(username, password))
                if (resp.isSuccessful) {
                    resp.body()!!.toUser()
                } else {
                    throw Exception(resp.errorBody()?.string()?.take(120) ?: "登录失败")
                }
            }
        }

    suspend fun changePassword(token: String, oldPassword: String, newPassword: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.changePassword(
                    "Bearer $token",
                    ChangePasswordRequest(oldPassword, newPassword)
                )
                if (resp.isSuccessful) {
                    resp.body()?.message ?: "密码已更新"
                } else {
                    throw Exception(resp.errorBody()?.string()?.take(120) ?: "修改失败")
                }
            }
        }

    suspend fun uploadAvatar(token: String, avatar: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.uploadAvatar("Bearer $token", AvatarRequest(avatar))
                if (resp.isSuccessful) resp.body()?.avatar ?: avatar
                else throw Exception(resp.errorBody()?.string()?.take(120) ?: "上传失败")
            }
        }

    /** 从服务器拉取最新资料（用于登录态校验与头像刷新）。 */
    suspend fun me(token: String): Result<User> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.me("Bearer $token")
                if (resp.isSuccessful) {
                    val u = resp.body()!!.user
                    User(
                        id = u.id.toString(),
                        username = u.username,
                        displayName = u.username,
                        avatar = u.avatar,
                        createdAt = u.created_at * 1000L,
                        token = token
                    )
                } else {
                    throw Exception(resp.errorBody()?.string()?.take(120) ?: "校验失败")
                }
            }
        }

    private fun AuthResponse.toUser(): User {
        val u = user
        return User(
            id = u.id.toString(),
            username = u.username,
            displayName = u.username,
            avatar = u.avatar,
            createdAt = u.created_at * 1000L,
            token = token
        )
    }
}
