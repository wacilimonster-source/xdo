package com.xdo.app.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.xdo.app.dl.AppPrefsHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 全局更新管理器：
 * - 更新状态跨页面共享（主界面、设置页都能看到检查/下载进度）；
 * - 支持启动时自动检查（受 AppPrefs.lastAutoUpdateCheck 节流，默认 6 小时一次）；
 * - 下载进度实时可看（百分比 + 已下载/总字节）。
 */
object UpdateManager {

    private const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var app: Application? = null

    fun init(app: Application) {
        this.app = app
    }

    private fun app(): Application = app ?: error("UpdateManager not initialized")

    /** 启动时自动检查：距上次检查超过 6 小时才请求一次；失败也记录，避免冷启动反复打网络 */
    fun autoCheck() {
        val prefs = AppPrefsHolder.get(app())
        val last = prefs.lastAutoUpdateCheck
        val now = System.currentTimeMillis()
        if (now - last < AUTO_INTERVAL_MS) return
        checkNow(force = true)
    }

    /** 手动/强制检查 */
    fun checkNow(force: Boolean = false) {
        if (_state.value == UpdateState.Checking) return
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = UpdateChecker.check()
            if (force) AppPrefsHolder.get(app()).lastAutoUpdateCheck = System.currentTimeMillis()
        }
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /** 下载并安装更新（进度实时写入全局状态，任何页面都可看到） */
    fun downloadAndInstall(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        scope.launch {
            _state.value = UpdateState.Downloading(0)
            val apk = withContext(Dispatchers.IO) {
                downloadApk(info.apkUrl) { bytes, total ->
                    val pct = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 99) else 0
                    _state.value = UpdateState.Downloading(pct, bytes, total)
                }
            }
            if (apk == null) {
                _state.value = UpdateState.DownloadFailed("下载失败，请检查网络")
                return@launch
            }
            val context = app()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
            _state.value = UpdateState.Idle
        }
    }

    private suspend fun downloadApk(url: String, onProgress: (Long, Long) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful || resp.body == null) return@use null
                    val body = resp.body!!
                    val total = body.contentLength()
                    val dir = File(app().cacheDir, "xdo")
                    dir.mkdirs()
                    val target = File(dir, "xdo_update.apk")
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                out.write(buf, 0, n)
                                written += n
                                onProgress(written, total)
                            }
                        }
                    }
                    target
                }
            }.getOrNull()
        }
}