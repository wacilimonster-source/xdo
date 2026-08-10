package com.xdo.app.ui.login

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.net.XLogin

private const val LOGIN_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/**
 * App 内 WebView 登录 X：打开 x.com/login，用户登录成功后从 CookieManager
 * 抠出 auth_token + ct0 存为 X 登录 Cookie。若 X 拒绝 WebView 登录，
 * 用户可改用设置页「粘贴 Cookie」手动填入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginXScreen(onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("正在打开 X 登录页…") }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录 X 账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LinearProgressIndicator(
                progress = { if (loading) 1f else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = LOGIN_UA
                        cm.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                if (saved) return
                                val cookie = extractXCookie(cm)
                                if (cookie != null) {
                                    saved = true
                                    status = "已获取登录凭据 ✓，可返回设置页"
                                    XLogin.applyCookie(cookie, AppPrefsHolder.get(ctx))
                                } else {
                                    status = "请在页面中登录你的 X 账号…"
                                }
                            }
                        }
                        loadUrl("https://x.com/login")
                    }
                },
            )
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            TextButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("完成")
            }
        }
    }
}

/** 从 CookieManager 提取 X 登录 Cookie；仅当同时具备 auth_token 与 ct0 时返回 */
private fun extractXCookie(cm: CookieManager): String? {
    val sb = StringBuilder()
    for (url in arrayOf("https://x.com", "https://twitter.com")) {
        val c = cm.getCookie(url)
        if (!c.isNullOrBlank()) sb.append(c).append("; ")
    }
    val joined = sb.toString().trimEnd(' ', ';')
    if (joined.isEmpty()) return null
    val hasAuth = joined.contains("auth_token=")
    val hasCt0 = joined.contains("ct0=")
    return if (hasAuth && hasCt0) joined else null
}
