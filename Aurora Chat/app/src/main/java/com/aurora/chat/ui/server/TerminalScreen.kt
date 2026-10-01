package com.aurora.chat.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全屏终端 Dialog
 */
@Composable
fun TerminalDialog(onDismiss: () -> Unit) {
    var showExitConfirm by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var commandHistory by remember { mutableStateOf(listOf<String>()) }

    // 退出确认弹窗
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text("退出终端", color = Color.White, fontSize = 17.sp)
            },
            text = {
                Text("确定退出终端？", color = Color(0xFFB0B0B0), fontSize = 14.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    onDismiss()
                }) {
                    Text("确定", color = Color(0xFF4FC3F7))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text("取消", color = Color(0xFF9E9E9E))
                }
            }
        )
    }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            // 当水平滑动结束时，如果累计位移超阈值则弹出确认
                        },
                        onHorizontalDrag = { _, _ ->
                            // 检测到水平滑动就弹确认
                            showExitConfirm = true
                        }
                    )
                }
        ) {
            // 终端内容区域
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Spacer(Modifier.height(32.dp))

                // 提示行
                Text(
                    text = "Aurora Terminal v1.0",
                    color = Color(0xFF4FC3F7),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "输入命令开始，左右滑动退出",
                    color = Color(0xFF616161),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(Modifier.height(16.dp))

                // 历史命令
                commandHistory.forEach { cmd ->
                    Row {
                        Text(
                            text = "$ ",
                            color = Color(0xFF4FC3F7),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = cmd,
                            color = Color(0xFFE0E0E0),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // 当前输入行
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$ ",
                        color = Color(0xFF4FC3F7),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    BasicTextField(
                        value = inputText,
                        onValueChange = {
                            // 检测到换行就提交命令
                            if (it.contains('\n')) {
                                val cmd = it.replace("\n", "").trim()
                                if (cmd.isNotEmpty()) {
                                    commandHistory = commandHistory + cmd
                                }
                                inputText = ""
                            } else {
                                inputText = it
                            }
                        },
                        textStyle = TextStyle(
                            color = Color(0xFFE0E0E0),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(Color(0xFF4FC3F7)),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.None
                        ),
                        singleLine = false
                    )
                }
            }
        }
    }
}
