package com.pm.manager.terminal

import com.pm.manager.model.SiteProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 平台 owner/管理员状态：由服务端实时确认，用于决定设置页是否显示反代入口。 */
data class ProxyOwnerState(
    val isOwner: Boolean = false,
    val perm: String = "B",
    val domain: String = ""
) {
    /** 服务端确认的管理员身份（perm==A），不依赖本地可能过期的存档。 */
    val isAdmin: Boolean get() = perm.equals("A", ignoreCase = true)
}

/**
 * 反向代理客户端：对接 server/site_server.py 的 /api/proxy-* 与 /api/admin-*。
 *
 * 安全边界由后端强制：proxy-* 仅 A 级且锁定到卡绑定域名；admin-* 仅 owner 卡密且需附管理口令。
 * 客户端不持久化任何口令，只在审核动作发生时临时提交。
 */
object ProxyApi {

    /** 查询当前卡是否是 owner（设置页据此显示"管理后台"入口）。 */
    suspend fun ownerState(): ProxyOwnerState = io {
        val res = SiteApi.postJson("/api/proxy-owner", emptyMap())
        if (!res.optBoolean("ok", false)) ProxyOwnerState()
        else ProxyOwnerState(
            isOwner = res.optBoolean("is_owner", false),
            perm = res.optString("perm", "B"),
            domain = res.optString("domain", "")
        )
    }

    /** A 级用户列出自己绑定域名下的反代记录（App 管理项 + 站点 nginx 里已存在的只读项）。 */
    suspend fun list(): List<SiteProxy> = io {
        val res = SiteApi.postJson("/api/proxy-list", emptyMap())
        checkOk(res, "获取反代列表失败")
        parse(res.optJSONArray("items")) + parse(res.optJSONArray("existing"))
    }

    /** 新增一条反代（进待审态）。成功后返回新记录。 */
    suspend fun add(p: SiteProxy): SiteProxy = io {
        val res = SiteApi.postJson("/api/proxy-add", payload(p))
        checkOk(res, "新增失败")
        parseItems(res)
    }

    /** 修改一条自身反代（修改后回到待审态，需重新审核生效）。 */
    suspend fun update(p: SiteProxy): SiteProxy = io {
        val map = payload(p).toMutableMap()
        map["id"] = p.id
        val res = SiteApi.postJson("/api/proxy-update", map)
        checkOk(res, "修改失败")
        parseItems(res)
    }

    /** 删除一条自身反代（若已生效，会同步移除相应 nginx 配置）。 */
    suspend fun delete(id: Long) = io {
        val res = SiteApi.postJson("/api/proxy-delete", mapOf("id" to id))
        checkOk(res, "删除失败")
    }

    // ==================== owner 管理后台 ====================

    /** owner 列出所有域名下的反代记录（含待审）。需附管理口令。 */
    suspend fun adminList(password: String): List<Pair<String, SiteProxy>> = io {
        val res = SiteApi.postJson("/api/admin-list", mapOf("admin_password" to password))
        checkOk(res, "获取审核列表失败")
        val arr = res.optJSONArray("items") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val dom = o.optString("domain", "")
                add(dom to parseOne(o))
            }
        }
    }

    /** owner 放行一条反代：成功后写 nginx 并 reload。 */
    suspend fun approve(domain: String, id: Long, password: String) = io {
        val res = SiteApi.postJson(
            "/api/admin-approve",
            mapOf("admin_password" to password, "domain" to domain, "id" to id)
        )
        checkOk(res, "放行失败")
    }

    /** owner 驳回一条反代。 */
    suspend fun reject(domain: String, id: Long, password: String) = io {
        val res = SiteApi.postJson(
            "/api/admin-reject",
            mapOf("admin_password" to password, "domain" to domain, "id" to id)
        )
        checkOk(res, "驳回失败")
    }

    // ==================== 内部 ====================

    private fun payload(p: SiteProxy): Map<String, Any?> = mapOf(
        "name" to p.name,
        "listen_path" to p.listenPath,
        "target_host" to p.targetHost,
        "target_port" to p.targetPort,
        "target_path" to p.targetPath
    )

    private fun parse(arr: JSONArray?): List<SiteProxy> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(parseOne(o))
            }
        }
    }

    private fun parseItems(res: JSONObject): SiteProxy {
        val o = res.optJSONObject("item")
            ?: throw ApiException(res.optString("error", "响应异常"))
        return parseOne(o)
    }

    private fun parseOne(o: JSONObject): SiteProxy = SiteProxy(
        id = o.optLong("id", 0L),
        name = o.optString("name", ""),
        listenPath = o.optString("listen_path", ""),
        targetHost = o.optString("target_host", ""),
        targetPort = o.optInt("target_port", 0),
        targetPath = o.optString("target_path", ""),
        status = o.optString("status", "pending"),
        updatedTime = o.optLong("updated_time", 0L),
        readonly = o.optBoolean("readonly", false) || o.optString("source", "") == "existing"
    )

    private fun checkOk(res: JSONObject, fallback: String) {
        if (!res.optBoolean("ok", false)) {
            throw ApiException(res.optString("error", "").ifBlank { fallback })
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}