package com.aurora.chat.host

/** 远程文件条目（由 site_server /api/list 下发）。 */
data class HostFile(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedMillis: Long
)

/** 备份条目（由 /api/backup-list 下发）。 */
data class HostBackup(
    val id: Long,
    val name: String,
    val size: Long,
    val time: Long
)

/** 业务异常：后端返回 ok=false 或响应无法解析，message 可直接展示给用户。 */
class ApiException(message: String) : Exception(message)

/** 网络不可达 / 超时 / 连接被重置，与业务错误区分。 */
class NetworkException(message: String = "网络异常，请检查网络连接") : Exception(message)
