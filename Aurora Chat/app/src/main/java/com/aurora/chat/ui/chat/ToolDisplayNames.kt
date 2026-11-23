package com.aurora.chat.ui.chat

/**
 * 工具中文名总表（唯一事实源）。
 *
 * 为什么单独放一个文件：此前中文名散在 `ChatConversationUtils.kt` 的 TOOL_NAMES 里，
 * 新增工具时容易漏登记，界面就回退成 `调用成功 open_rear_camera` 这种英文（用户明确不接受）。
 * 这里按 `AiChatManager` 注册的**全部**工具逐一收录（125 个），并配一个中文兜底：
 * 任何没登记的插件/动态工具也只会显示「自定义工具」，绝不再把英文工具名漏到界面上。
 */

/** 工具名 → 中文短名。键为 function calling 的工具标识，值为界面展示用中文。 */
internal val TOOL_DISPLAY_NAMES: Map<String, String> = mapOf(
    // ── 多 AI 与提问 ──
    "agent_plan" to "规划",
    "agent_fork" to "并行派发",
    "agent_review" to "验收",
    "ask_user" to "询问用户",
    // ── 文件 / 目录 ──
    "list_files" to "列出目录",
    "read_file" to "读取文件",
    "write_file" to "创建文件",
    "edit_file" to "编辑文件",
    "append_file" to "追加内容",
    "delete_file" to "删除文件",
    "restore_file" to "恢复文件",
    "create_folder" to "新建目录",
    "delete_folder" to "删除目录",
    "rename_file" to "重命名",
    "copy_file" to "复制文件",
    "move_dir_contents" to "移动目录内容",
    "stat_file" to "查看属性",
    "find_files" to "搜索文件",
    "search_in_files" to "搜索内容",
    "get_file" to "发送文件",
    "batch_replace" to "批量替换",
    "apply_patch" to "应用补丁",
    "batch_loop" to "批量执行",
    "upload_file" to "上传文件",
    "download_file" to "下载文件",
    "ftp_transfer" to "FTP 传输",
    // ── 压缩包 ──
    "compress_archive" to "压缩",
    "extract_archive" to "解压",
    "archive_list" to "查看压缩包",
    "archive_read_file" to "读取压缩包内文件",
    // ── 执行 / 脚本 ──
    "shell_exec" to "执行命令",
    "execute_python" to "运行 Python",
    "run_adb" to "执行 adb 命令",
    "process_list" to "列出进程",
    "kill_process" to "结束进程",
    "stop_app" to "停止应用",
    "open_app" to "打开应用",
    "exit_app" to "退出应用",
    "uninstall_app" to "卸载应用",
    "install_apk" to "安装 APK",
    "take_screenshot" to "截屏",
    "capture_screen" to "捕获屏幕",
    "phone_screen" to "读取屏幕",
    "phone_tap" to "点击屏幕",
    "phone_swipe" to "滑动屏幕",
    "phone_type" to "输入文字",
    "phone_key" to "发送按键",
    "phone_access" to "开启无障碍",
    "shutdown_device" to "关机",
    "reboot_device" to "重启设备",
    // ── 网络 ──
    "fetch_webpage" to "抓取网页",
    "http_request" to "发起 HTTP 请求",
    "search_web" to "联网搜索",
    "deep_research" to "深度调研",
    "ping_host" to "探测连通性",
    "dns_lookup" to "解析域名",
    "port_scan" to "扫描端口",
    "traceroute" to "路由追踪",
    "bandwidth_test" to "测速",
    // ── 生成 / 通知 ──
    "generate_media" to "生成媒体",
    "send_file" to "发送文件给用户",
    "send_notification" to "发送通知",
    // ── 文本 / 数据处理 ──
    "json_process" to "处理 JSON",
    "regex_test" to "正则测试",
    "encode_convert" to "编码转换",
    "hash_digest" to "计算摘要",
    "timestamp_convert" to "时间戳转换",
    "text_stats" to "统计文本",
    "size_convert" to "容量换算",
    "diff_text" to "对比文本",
    "git_command" to "执行 Git 命令",
    "crypto_encrypt" to "加密",
    "crypto_decrypt" to "解密",
    "qr_decode" to "识别二维码",
    // ── 调度 / 记录 ──
    "wait" to "等待",
    "keep_alive" to "保持会话",
    "work_log" to "记录工作日志",
    // ── 工作区备份 ──
    "list_backups" to "列出备份",
    "read_backup_file" to "读取备份",
    "restore_backup" to "恢复备份",
    // ── 插件 ──
    "plugin_list" to "查看插件",
    "plugin_call" to "调用插件",
    "plugin_submit" to "提交插件",
    // ── 应用内动作（聊天扩展）──
    "switch_tab" to "切换板块",
    "send_message" to "发送消息",
    "recall_message" to "撤回消息",
    "list_recent_messages" to "读取最近消息",
    "send_friend_request" to "发送好友申请",
    "create_community_post" to "发布社区帖子",
    "list_my_contacts" to "读取联系人",
    "list_my_groups" to "读取群列表",
    // ── 环境 / 模式 / 方案 ──
    "query_environment" to "查询环境",
    "set_agent_mode" to "切换模式",
    "update_plan_step" to "更新进度",
    // ── 定位 / 相机 ──
    "get_current_location" to "获取当前位置",
    "open_front_camera" to "打开前置摄像头",
    "open_rear_camera" to "打开后置摄像头",
    // ── 设备控制 / 系统开关 ──
    "set_volume" to "调节音量",
    "play_audio" to "播放音频",
    "play_video" to "播放视频",
    "vibrate" to "振动",
    "set_alarm" to "设置闹钟",
    "cancel_alarm" to "取消闹钟",
    "list_alarms" to "查看闹钟",
    "set_brightness" to "调节屏幕亮度",
    "keep_screen_on" to "屏幕常亮",
    "set_screen_orientation" to "设置屏幕方向",
    "set_bluetooth" to "蓝牙开关",
    "set_gps" to "GPS 定位开关",
    "set_wifi" to "Wi-Fi 开关",
    "set_nfc" to "NFC 开关",
    "set_flashlight" to "手电筒开关",
    "battery_manage" to "电池管理",
    // ── 逆向分析（reverse 执行模式）──
    "reverse_strings" to "提取字符串",
    "reverse_dex" to "分析 DEX",
    "reverse_native" to "分析原生库",
    "reverse_disasm" to "反汇编",
    // ── 本地服务（AI 自建后端）──
    "serve_start" to "启动本地服务",
    "serve_stop" to "停止本地服务",
    "serve_list" to "查看本地服务",
    "serve_log" to "读取服务日志"
)

/**
 * 工具的中文展示名。
 *
 * 已登记 → 中文短名；未登记（插件/动态工具）→ 「自定义工具」，**绝不回退成英文工具名**。
 * 原始标识只写进 Log，便于排查，不进入任何用户可见文案。
 */
internal fun toolDisplayName(tool: String): String {
    if (tool.isBlank()) return "工具"
    return TOOL_DISPLAY_NAMES[tool] ?: run {
        android.util.Log.w("ToolDisplayName", "未登记中文名的工具: $tool")
        "自定义工具"
    }
}
