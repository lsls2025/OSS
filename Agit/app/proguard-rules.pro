# Agit 发布混淆规则（当前未启用 minify）
-dontwarn org.jetbrains.annotations.**
-keepattributes *Annotation*

# 保留数据模型（如需 Room/序列化再调整）
-keep class com.agit.app.data.model.** { *; }
