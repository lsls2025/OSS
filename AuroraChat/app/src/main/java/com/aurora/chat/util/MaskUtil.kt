package com.aurora.chat.util

/**
 * 资料脱敏（用于「不显示 QQ 号 / 不显示邮箱」两个隐私开关）。
 *
 * 说明：这两项不是"完全隐藏"，而是**打码展示**——用户仍然能看出对方填过什么、
 * 也保留可辨识的前缀，但完整值不可读。规则：
 *
 *   QQ：长度 >= 7 → 前 3 + *** + 后 2     例 123456789 → 123***89
 *       长度 4..6 → 首 1 + *** + 尾 1     例 123456    → 1***6
 *       长度 <= 3 → 首 1 + ***            例 123       → 1***
 *
 *   邮箱：保留 @ 前的 1~2 个字符 + *** + @域名
 *       例 zhangsan@qq.com → zh***@qq.com
 *
 * 只做展示层，原值不落任何日志。
 */
object MaskUtil {

    fun qq(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        // 幂等：服务端（friend_handlers.go maskQQ）可能已经打过码，
        // 再打一次会把尾号又截掉一节，所以已含 * 就原样返回。
        if (s.contains('*')) return s
        return when {
            s.length >= 7 -> s.take(3) + "***" + s.takeLast(2)
            s.length >= 4 -> s.first() + "***" + s.last()
            else -> s.first() + "***"
        }
    }

    fun email(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        if (s.contains('*')) return s
        val at = s.indexOf('@')
        if (at <= 0) {
            return if (s.length >= 3) s.take(2) + "***" else s.first() + "***"
        }
        val name = s.substring(0, at)
        val domain = s.substring(at) // 含 @
        val head = if (name.length >= 3) name.take(2) else name.take(1)
        return head + "***" + domain
    }
}
