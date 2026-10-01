# Default ProGuard rules
-keep class com.aurora.chat.** { *; }

# R8 full mode 下保留必要的属性与元数据，避免运行时反射/序列化/Compose 稳定性推断出错
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# 第三方库在 R8 full mode 下可能触发误报，抑制告警
-dontwarn com.tencent.mmkv.**
-dontwarn androidx.profileinstaller.**
-dontwarn okio.**
-dontwarn org.json.**

# 手写 JSON（org.json）序列化：保留被反射访问的数据类（本包已整体 keep，这里仅兜底）
-keep class org.json.** { *; }

# LuaJ 依赖 javax.script 接口，Android 缺失但运行时未用到，抑制 R8 缺失类报错
-dontwarn javax.script.**
-dontwarn org.luaj.vm2.script.**

# LuaJ 内部类/方法在 R8 full mode 下会被过度收缩，导致运行时 NoClassDefFoundError
-keep class org.luaj.vm2.** { *; }
-keepclassmembers class org.luaj.vm2.** { *; }

# LuaJ 的 luajc 编译器依赖 Apache BCEL，Android 不存在且运行时未用到
-dontwarn org.apache.bcel.**
