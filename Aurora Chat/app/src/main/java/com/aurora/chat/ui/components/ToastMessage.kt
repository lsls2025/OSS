package com.aurora.chat.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ToastHost(
    toastMessage: String?,
    onDismiss: () -> Unit
) {
    if (toastMessage == null) return

    // 自动消失
    LaunchedEffect(toastMessage) {
        delay(1800)
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(300))
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = toastMessage,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}
