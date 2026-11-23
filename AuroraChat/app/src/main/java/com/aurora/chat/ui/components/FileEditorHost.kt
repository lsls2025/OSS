package com.aurora.chat.ui.components

import androidx.compose.runtime.Composable

/**
 * 全屏文件编辑器宿主模块（由「钢琴工作区」的编辑器封装提炼而来）。
 *
 * 统一入口封装 CodeEditor：各文件管理页（工作区 / 网站托管）不再直接散调
 * CodeEditor，统一经本模块渲染；读写数据通道由调用方通过 onSave / onDismiss 注入。
 *
 * @param title            顶栏标题（文件名 / 路径）
 * @param subtitle         顶栏副标题
 * @param initialContent   编辑内容（打开时由调用方读入）
 * @param isLoading        内容加载中
 * @param onSave           保存回调；closeAfter=true 表示保存后关闭
 * @param onDismiss        关闭回调
 * @param rootFile         本地关联文件（运行 .py 等脚本时使用；远程场景不传）
 */
@Composable
fun FileEditorHost(
    title: String,
    subtitle: String = "",
    initialContent: String,
    isLoading: Boolean,
    onSave: suspend (content: String, closeAfter: Boolean) -> Unit,
    onDismiss: () -> Unit,
    rootFile: java.io.File? = null
) {
    CodeEditor(
        title = title,
        subtitle = subtitle,
        initialContent = initialContent,
        isLoading = isLoading,
        onSave = onSave,
        onDismiss = onDismiss,
        rootFile = rootFile
    )
}
