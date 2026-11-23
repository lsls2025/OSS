# ============================================================
# R8 规则：只保留「必须按类名访问」的东西，其余交给混淆 + 裁剪
#
# 之前是 -keep class com.aurora.chat.** { *; }
# 那一条把全部自有类、全部方法、全部字段都保护住了，R8 除了缩进什么都做不了。
# 本项目没有任何按类名反射创建对象的代码（JSON 全是手写 org.json，
# 不用 Gson/Moshi/kotlinx.serialization），所以这个全量 keep 完全是多余的。
#
# 回滚：把 app/proguard-rules.pro.bak 覆盖回来即可。
# ============================================================

# 注解、泛型签名、内部类等元数据：反射/Compose/协程依赖
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# Manifest 里按类名字符串实例化的组件：类名不能变。
# 注意**不能**带 { *; } —— 那会把所有方法都钉死，等于没优化。
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
# AccessibilityService 不直接继承 android.app.Service，上面那条盖不住它
-keep public class * extends android.accessibilityservice.AccessibilityService

# 全项目唯一被 Class.forName 按字符串取的自有类（读 DEBUG 开关）
-keep class com.aurora.chat.BuildConfig { *; }

# ============================================================
# R8 VerifyError 兜底（2026-11-09）
#
# 现象：Release（开 R8）进任意对话闪退，Debug 正常，日志是
#   java.lang.VerifyError: Verifier rejected class f21: void f21.d(…)
#   [0x152E] type Conflict unexpected as arg to if-eqz/if-nez
# f21 = com.aurora.chat.ui.chat.ChatConversationScreenKt（见 mapping.txt）。
#
# 原因：这个文件里有一个参数两百多个的巨型 @Composable，R8 full mode 在它身上
# 做内联 / 类合并后，生成的字节码让 ART 校验器判出「类型冲突」，整类被拒。
# 属于优化器的问题，不是业务逻辑错。
#
# 处理：这一个类禁止优化（-keep 默认就含 no-optimization），其余类照旧全速优化。
# 代价只有这一个类的体积/速度，APK 其余部分不受影响。
# ============================================================
-keep class com.aurora.chat.ui.chat.ChatConversationScreenKt { *; }

# 第三方库在 R8 full mode 下的误报抑制
-dontwarn com.tencent.mmkv.**
-dontwarn androidx.profileinstaller.**
-dontwarn okio.**

# org.json 手写序列化：保留（AGP 默认规则也含，这里显式兜底）
-keep class org.json.** { *; }

# LuaJ 时代残留规则已于 2026-10-13 移除（LuaJ 已从项目剔除，无任何类引用 org.luaj/javax.script/org.apache.bcel）

