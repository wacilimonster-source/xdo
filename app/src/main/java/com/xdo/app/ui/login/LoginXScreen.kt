package com.xdo.app.ui.login

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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

private const val X_LOGIN_URL = "https://x.com/i/flow/login"
private const val TWITTER_LOGIN_URL = "https://twitter.com/i/flow/login"

/**
 * X 登录页。
 * X 的登录页是 React 单页应用，部分 Android WebView 内核会加载到空 DOM。
 * 页面内容为空时自动切换到 twitter.com 旧入口，并提供重试按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginXScreen(onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("正在打开 X 登录页…") }
    var saved by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var usingFallback by remember { mutableStateOf(false) }
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
                    createLoginWebView(
                        context = ctx,
                        onLoading = {
                            loading = true
                            hasError = false
                        },
                        onStatus = { status = it },
                        onSaved = {
                            saved = true
                            loading = false
                        },
                        onError = {
                            loading = false
                            hasError = true
                            status = it
                        },
                        onEmptyPage = { webView ->
                            if (!usingFallback) {
                                usingFallback = true
                                status = "X 登录页未渲染，正在切换兼容入口…"
                                webView.loadUrl(TWITTER_LOGIN_URL)
                            } else {
                                loading = false
                                hasError = true
                                status = "登录页内容无法加载，请更新系统 WebView 或改用粘贴 Cookie"
                            }
                        },
                    ).also {
                        webViewRef = it
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
                        usingFallback = false
                        status = "正在重新加载 X 登录页…"
                        webViewRef?.loadUrl(X_LOGIN_URL)
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

private fun createLoginWebView(
    context: Context,
    onLoading: () -> Unit,
    onStatus: (String) -> Unit,
    onSaved: () -> Unit,
    onError: (String) -> Unit,
    onEmptyPage: (WebView) -> Unit,
): WebView {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    return WebView(context).apply {
        setBackgroundColor(android.graphics.Color.WHITE)
        // 某些小米设备的 WebView GPU 合成会把跨域 React 页面合成为纯白。
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 使用设备 WebView 的真实 UA，不伪装为 Windows/Pixel，减少 X 风控误判。
            userAgentString = userAgentString
        }
        cookieManager.setAcceptThirdPartyCookies(this, true)
        // 上一轮失败可能留下残缺 guest/session Cookie，先清空再启动全新登录流程。
        clearCache(true)
        clearHistory()
        cookieManager.removeAllCookies {
            cookieManager.flush()
            post { loadUrl(X_LOGIN_URL) }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    onStatus("登录页脚本错误：${message.message().take(80)}")
                }
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onLoading()
                onStatus("正在加载 X 登录页…")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == null) return
                val cookie = extractXCookie(cookieManager)
                if (cookie != null) {
                    onSaved()
                    onStatus("已获取登录凭据 ✓，可点击完成返回")
                    XLogin.applyCookie(cookie, AppPrefsHolder.get(context))
                    return
                }
                // onPageFinished 只表示 HTML 到达，不代表 React 已经挂载；延迟检测可见内容。
                view.postDelayed({
                    view.evaluateJavascript(
                        "(function(){var b=document.body;return b?(b.innerText.length+'|'+document.documentElement.innerHTML.length):'0|0';})()",
                    ) { result ->
                        val numbers = Regex("(\\d+)\\|(\\d+)").find(result ?: "")
                        val textLength = numbers?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val htmlLength = numbers?.groupValues?.get(2)?.toIntOrNull() ?: 0
                        if (textLength < 5 && htmlLength < 1000) onEmptyPage(view)
                        else {
                            onStatus("请在页面中登录你的 X 账号…")
                        }
                    }
                }, 1500L)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onError("X 登录页加载失败：${error?.description ?: "网络异常"}")
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: SslError?,
            ) {
                handler?.cancel()
                onError("X 登录页证书校验失败，请检查系统时间或网络代理")
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
