package com.pm.manager.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.pm.manager.ui.components.TopBar

/** 把服务器下发的 HTML 内容渲染出来（用于预览网页类文件）。 */
@Composable
fun HtmlBrowserScreen(title: String, html: String, baseUrl: String, onClose: () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        TopBar(title = title, onBack = onClose)
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
