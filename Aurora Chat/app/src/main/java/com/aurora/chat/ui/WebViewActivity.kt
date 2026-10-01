package com.aurora.chat.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 应用内 WebView 浏览器 — 点击链接时选择"内部打开"时启动。
 * 统一为 Compose（WebView 通过 AndroidView 承载，因其本质是原生 View）。
 */
class WebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val titleInput = intent.getStringExtra(EXTRA_TITLE) ?: url

        setContent {
            MaterialTheme {
                var progress by remember { mutableStateOf(0) }
                var isLoading by remember { mutableStateOf(true) }

                BackHandler { finish() }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(titleInput) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(Modifier.fillMaxSize().padding(innerPadding)) {
                        if (isLoading) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(3.dp)
                            )
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, vUrl: String?, favicon: android.graphics.Bitmap?) {
                                            isLoading = true
                                        }

                                        override fun onPageFinished(view: WebView?, vUrl: String?) {
                                            isLoading = false
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val reqUrl = request?.url?.toString() ?: return false
                                            if (reqUrl.startsWith("tel:") || reqUrl.startsWith("mailto:")) {
                                                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(reqUrl)))
                                                return true
                                            }
                                            return false
                                        }
                                    }
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            progress = newProgress
                                        }
                                    }
                                    loadUrl(url)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
