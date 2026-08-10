package com.xdo.app.ui.login

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.net.XLogin

// 使用 Android Chrome UA，避免 X 根据 Windows UA 下发与 WebView 不兼容的桌面登录页。
// 同时去掉常见的 WebView "wv" 标识，降低 X 返回空白页的概率。
private const val LOGIN_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.50 Mobile Safari/537.36"

private const val LOGIN_URL = "https://x.com/i/flow/login"

/**
 * App 内 WebView 登录 X：打开登录流程，用户登录成功后从 CookieManager
 * 抠出 auth_token + ct0 存为 X 登录 Cookie。若 X 拒绝 WebView 登录，
 * 用户可改用设置页「粘贴 Cookie」手动填入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginXScreen(onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("正在打开 X 登录页…") }
    var saved by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

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
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    WebView(ctx).apply {
                        webViewRef = this
                        setBackgroundColor(android.graphics.Color.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = LOGIN_UA
                        cm.setAcceptThirdPartyCookies(this, true)
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                loading = true
                                hasError = false
                                status = "正在加载 X 登录页…"
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                if (saved) return
                                val cookie = extractXCookie(cm)
                                if (cookie != null) {
                                    saved = true
                                    status = "已获取登录凭据 ✓，可点击完成返回"
                                    XLogin.applyCookie(cookie, AppPrefsHolder.get(ctx))
                                } else if (!hasError) {
                                    status = "请在页面中登录你的 X 账号…"
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    hasError = true
                                    status = "X 登录页加载失败：${error?.description ?: "网络异常"}"
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: android.webkit.SslErrorHandler?,
                                error: SslError?,
                            ) {
                                // 不接受异常证书，避免中间人攻击；用户可通过重新加载恢复。
                                handler?.cancel()
                                loading = false
                                hasError = true
                                status = "X 登录页证书校验失败，请检查系统时间或网络代理"
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
                update = { webViewRef = it },
            )
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (hasError) {
                Button(
                    onClick = {
                        hasError = false
                        status = "正在重新加载 X 登录页…"
                        webViewRef?.loadUrl(LOGIN_URL)
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("重新加载")
                }
            }
            TextButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(if (saved) "完成" else "返回（也可改用粘贴 Cookie）")
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
