package com.pm.manager.model

import androidx.compose.runtime.Immutable

/**
 * 反向代理记录：对应后端 /api/proxy-* 返回的 public 字段。
 * status: pending=待审核(未生效) / approved=已生效 / rejected=已驳回 / existing=站点已有(只读)。
 * readonly=true 表示该记录是站点 nginx 里已存在的反向代理，只读展示，不可编辑/删除。
 * 数据按绑定域名隔离，每个 A 级用户只能看到自己的站点。
 */
@Immutable
data class SiteProxy(
    val id: Long,
    val name: String,
    val listenPath: String,
    val targetHost: String,
    val targetPort: Int,
    val targetPath: String,
    val status: String,
    val updatedTime: Long,
    val readonly: Boolean = false
) {
    val isExisting: Boolean get() = status == "existing" || readonly

    fun statusLabel(): String = when (status) {
        "approved" -> "已生效"
        "rejected" -> "已驳回"
        "existing" -> "站点已有"
        else -> "待审核"
    }
}