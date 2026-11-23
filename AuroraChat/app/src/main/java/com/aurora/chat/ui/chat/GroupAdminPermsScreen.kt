package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 权限项元数据：key 与后端 allGroupPermKeys 一一对应 */
private data class GroupPermMeta(val key: String, val title: String, val desc: String)

private val GROUP_PERM_METAS = listOf(
    GroupPermMeta("group_name", "修改群名称", "改群聊显示名"),
    GroupPermMeta("group_signature", "修改群签名", "改群签名一句话介绍"),
    GroupPermMeta("group_announcement", "管理群公告", "发布、删除群公告"),
    GroupPermMeta("group_welcome", "修改群欢迎语", "改新成员入群欢迎语"),
    GroupPermMeta("group_avatar", "修改群头像", "更换群聊头像"),
    GroupPermMeta("group_notify", "群通知方式", "选群身份通知或系统广播"),
    GroupPermMeta("mute", "禁言成员", "禁言与解除禁言"),
    GroupPermMeta("kick", "移除成员", "把成员请出群聊"),
    GroupPermMeta("msg_rate", "发言频率", "限制每人每分钟发言条数"),
    GroupPermMeta("review_join", "审批入群", "同意或拒绝入群申请"),
    GroupPermMeta("group_sensitive", "群敏感词", "设置敏感词与命中处置"),
    GroupPermMeta("group_blacklist", "群黑名单", "查看与解除拉黑"),
    GroupPermMeta("group_todo", "群待办", "设置与清除群待办")
)

/** 单个管理员的权限行 */
private data class AdminPermRow(
    val userId: Long,
    val username: String,
    val perms: MutableSet<String>,
    val customized: Boolean
)

/**
 * 管理设置（全屏界面，仅群主可进）：
 *
 *  - 「管理员默认权限」：对全体管理员生效的模板
 *  - 「单个管理员」：为某个人单独设定，优先于默认模板；可一键恢复跟随默认
 *
 * 群主自己永远拥有全部权限，不在这里配置。
 */
@Composable
fun GroupAdminPermsScreen(
    groupId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf("") }
    var defaultPerms by remember { mutableStateOf<MutableSet<String>>(mutableSetOf()) }
    var admins by remember { mutableStateOf<List<AdminPermRow>>(emptyList()) }
    // null = 正在配置默认模板；非 null = 正在配置该管理员的单独权限
    var editingAdmin by remember { mutableStateOf<AdminPermRow?>(null) }

    fun applyData(d: JSONObject) {
        defaultPerms = d.optJSONArray("default_perms")?.let { arr ->
            mutableSetOf<String>().apply { for (i in 0 until arr.length()) add(arr.optString(i)) }
        } ?: mutableSetOf()
        val list = mutableListOf<AdminPermRow>()
        d.optJSONArray("admins")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uid = o.optLong("user_id")
                val perms = mutableSetOf<String>()
                o.optJSONArray("perms")?.let { p ->
                    for (j in 0 until p.length()) perms.add(p.optString(j))
                }
                val name = o.optString("username").ifBlank { "用户$uid" }
                list.add(AdminPermRow(uid, name, perms, o.optBoolean("customized", false)))
            }
        }
        admins = list
    }

    LaunchedEffect(groupId) {
        val r = AuroraApi.getGroupAdminPerms(groupId)
        if (r.success && r.data != null) {
            applyData(r.data!!)
            loadFailed = ""
        } else {
            loadFailed = r.message.ifBlank { "加载失败" }
        }
        loading = false
    }

    // 保存：userId=0 存默认模板，否则存单个管理员
    fun persist(userId: Long, perms: Set<String>, rollback: () -> Unit) {
        scope.launch {
            val r = AuroraApi.saveGroupAdminPerms(groupId, userId, perms.toList())
            if (!r.success) {
                rollback()
                Toast.makeText(context, "保存失败：${r.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
            // ── 顶部栏：不透明白底，避免状态栏/背景内容从顶部透出来 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp).clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (editingAdmin != null) editingAdmin = null else onBack()
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    editingAdmin?.let { "${it.username} 的权限" } ?: "管理设置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(28.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = Color(0xFF1E40AF), strokeWidth = 3.dp)
                }

                loadFailed.isNotBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(loadFailed, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }

                else -> {
                    val targetAdmin = editingAdmin
                    LazyColumn(
                        Modifier.fillMaxSize().navigationBarsPadding(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)
                    ) {
                        if (targetAdmin == null) {
                            // ── 默认模板视图 ──
                            item {
                                SectionHeader(
                                    title = "管理员默认权限",
                                    actionText = if (defaultPerms.size == GROUP_PERM_METAS.size) "全部关闭" else "全部开启",
                                    onAction = {
                                        val target = if (defaultPerms.size == GROUP_PERM_METAS.size) emptySet()
                                        else GROUP_PERM_METAS.map { it.key }.toSet()
                                        val prev = defaultPerms.toSet()
                                        defaultPerms = target.toMutableSet()
                                        // 默认模板改了，跟随默认的管理员展示也要同步
                                        admins = admins.map {
                                            if (it.customized) it else it.copy(perms = target.toMutableSet())
                                        }
                                        persist(0L, target) { defaultPerms = prev.toMutableSet() }
                                    }
                                )
                            }
                            items(GROUP_PERM_METAS.size) { idx ->
                                val meta = GROUP_PERM_METAS[idx]
                                PermSwitchRow(
                                    meta = meta,
                                    checked = defaultPerms.contains(meta.key),
                                    onCheckedChange = { on ->
                                        val prev = defaultPerms.toSet()
                                        val next = defaultPerms.toMutableSet()
                                        if (on) next.add(meta.key) else next.remove(meta.key)
                                        defaultPerms = next
                                        admins = admins.map {
                                            if (it.customized) it else it.copy(perms = next.toMutableSet())
                                        }
                                        persist(0L, next) { defaultPerms = prev.toMutableSet() }
                                    }
                                )
                            }

                            item {
                                SectionHeader(title = "单个管理员", actionText = "", onAction = {})
                            }
                            if (admins.isEmpty()) {
                                item {
                                    Text(
                                        "该群还没有管理员，先在「群聊信息 → ⋯ → 设置管理」里添加。",
                                        fontSize = 13.sp,
                                        color = com.aurora.chat.ui.theme.appTextHint(),
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                    )
                                }
                            } else {
                                items(admins.size) { idx ->
                                    val a = admins[idx]
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) { editingAdmin = a }
                                            .padding(horizontal = 20.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        UserAvatar(userId = a.userId, userName = a.username, size = 36.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(a.username, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                if (a.customized) "单独设置 ${a.perms.size}/${GROUP_PERM_METAS.size} 项"
                                                else "跟随默认 ${a.perms.size}/${GROUP_PERM_METAS.size} 项",
                                                fontSize = 12.sp,
                                                color = if (a.customized) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint()
                                            )
                                        }
                                        Icon(Icons.Filled.ChevronRight, contentDescription = null,
                                            tint = Color(0xFFD1D5DB), modifier = Modifier.size(18.dp))
                                    }
                                    Box(Modifier.fillMaxWidth().padding(start = 20.dp).height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                                }
                            }
                        } else {
                            // ── 单个管理员视图 ──
                            item {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        UserAvatar(userId = targetAdmin.userId, userName = targetAdmin.username, size = 40.dp)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(targetAdmin.username, fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                if (targetAdmin.customized) "已单独设置，不受默认权限影响" else "当前跟随默认权限",
                                                fontSize = 12.sp,
                                                color = if (targetAdmin.customized) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint()
                                            )
                                        }
                                    }
                                    if (targetAdmin.customized) {
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            "恢复跟随默认",
                                            fontSize = 13.sp,
                                            color = Color(0xFF1E40AF),
                                            modifier = Modifier
                                                .clickable(
                                                    indication = null,
                                                    interactionSource = remember { MutableInteractionSource() }
                                                ) {
                                                    scope.launch {
                                                        val r = AuroraApi.saveGroupAdminPerms(groupId, targetAdmin.userId, emptyList(), reset = true)
                                                        if (r.success) {
                                                            admins = admins.map {
                                                                if (it.userId == targetAdmin.userId)
                                                                    it.copy(perms = defaultPerms.toMutableSet(), customized = false)
                                                                else it
                                                            }
                                                            editingAdmin = admins.firstOrNull { it.userId == targetAdmin.userId }
                                                            Toast.makeText(context, "已恢复跟随默认", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "操作失败：${r.message}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                                .padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            item {
                                SectionHeader(
                                    title = "权限项",
                                    actionText = if (targetAdmin.perms.size == GROUP_PERM_METAS.size) "全部关闭" else "全部开启",
                                    onAction = {
                                        val target = if (targetAdmin.perms.size == GROUP_PERM_METAS.size) emptySet()
                                        else GROUP_PERM_METAS.map { it.key }.toSet()
                                        val prev = targetAdmin.perms.toSet()
                                        admins = admins.map {
                                            if (it.userId == targetAdmin.userId)
                                                it.copy(perms = target.toMutableSet(), customized = true)
                                            else it
                                        }
                                        editingAdmin = admins.firstOrNull { it.userId == targetAdmin.userId }
                                        persist(targetAdmin.userId, target) {
                                            admins = admins.map {
                                                if (it.userId == targetAdmin.userId)
                                                    it.copy(perms = prev.toMutableSet(), customized = true)
                                                else it
                                            }
                                            editingAdmin = admins.firstOrNull { it.userId == targetAdmin.userId }
                                        }
                                    }
                                )
                            }
                            items(GROUP_PERM_METAS.size) { idx ->
                                val meta = GROUP_PERM_METAS[idx]
                                PermSwitchRow(
                                    meta = meta,
                                    checked = targetAdmin.perms.contains(meta.key),
                                    onCheckedChange = { on ->
                                        val prev = targetAdmin.perms.toSet()
                                        val next = prev.toMutableSet()
                                        if (on) next.add(meta.key) else next.remove(meta.key)
                                        admins = admins.map {
                                            if (it.userId == targetAdmin.userId)
                                                it.copy(perms = next, customized = true)
                                            else it
                                        }
                                        editingAdmin = admins.firstOrNull { it.userId == targetAdmin.userId }
                                        persist(targetAdmin.userId, next) {
                                            admins = admins.map {
                                                if (it.userId == targetAdmin.userId)
                                                    it.copy(perms = prev.toMutableSet(), customized = true)
                                                else it
                                            }
                                            editingAdmin = admins.firstOrNull { it.userId == targetAdmin.userId }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, actionText: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary(),
            modifier = Modifier.weight(1f))
        if (actionText.isNotBlank()) {
            Text(
                actionText,
                fontSize = 13.sp,
                color = Color(0xFF1E40AF),
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onAction() }
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            )
        }
    }
}

@Composable
private fun PermSwitchRow(meta: GroupPermMeta, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onCheckedChange(!checked) }
            .padding(start = 20.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                meta.title,
                fontSize = 15.sp,
                color = if (checked) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextHint()
            )
            Spacer(Modifier.height(2.dp))
            Text(meta.desc, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF1E40AF),
                checkedBorderColor = Color(0xFF1E40AF),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = com.aurora.chat.ui.theme.appDivider(),
                uncheckedBorderColor = com.aurora.chat.ui.theme.appDivider()
            )
        )
    }
}
