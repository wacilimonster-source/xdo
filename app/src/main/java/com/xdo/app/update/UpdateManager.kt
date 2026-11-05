package com.xdo.app.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
 *
 * B07：lastAvailable 保存最近一次「发现新版本」的 info，
 *      下载中/失败时弹窗仍能拿到版本信息继续展示。
 * B32：下载完成后校验字节数与签名一致性；安装失败给出明确提示，不再静默。
 */
object UpdateManager {

    private const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _lastAvailable = MutableStateFlow<UpdateInfo?>(null)
    val lastAvailable: StateFlow<UpdateInfo?> = _lastAvailable.asStateFlow()

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
            val result = UpdateChecker.check()
            if (result is UpdateState.Available) _lastAvailable.value = result.info
            _state.value = result
            if (force) AppPrefsHolder.get(app()).lastAutoUpdateCheck = System.currentTimeMillis()
        }
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /** 下载并安装更新（进度实时写入全局状态，任何页面都可看到） */
    fun downloadAndInstall(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        _lastAvailable.value = info
        scope.launch {
            _state.value = UpdateState.Downloading(0)
            val apk = withContext(Dispatchers.IO) {
                downloadApk(info.apkUrl) { bytes, total ->
                    val pct = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 99) else 0
                    _state.value = UpdateState.Downloading(pct, bytes, total)
                }
            }
            if (apk == null) {
                _state.value = UpdateState.DownloadFailed("下载失败或文件不完整，请检查网络后重试")
                return@launch
            }
            val context = app()

            // B32：安装前校验签名一致性，防止装到被篡改的包
            if (!apkSignatureMatches(context, apk)) {
                apk.delete()
                _state.value = UpdateState.DownloadFailed("安装包校验失败（签名不一致），已取消安装")
                return@launch
            }

            // B32：未授予「安装未知应用」权限时给出明确提示，而不是静默失败
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                _state.value = UpdateState.DownloadFailed(
                    "请先在系统设置中允许本应用「安装未知应用」，然后重试"
                )
                return@launch
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val started = runCatching { context.startActivity(intent) }.isSuccess
            _state.value = if (started) UpdateState.Idle
            else UpdateState.DownloadFailed("无法启动安装器，请重试")
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
                        // B32：完整性校验——字节数与 Content-Length 不符视为失败
                        val complete = total <= 0 || written == total
                        if (!complete) target.delete()
                        if (complete) target else null
                    }
                }
            }.getOrNull()
        }

    /** B32：对比 APK 签名与当前应用签名是否一致（兼容 API 26/27 的 GET_SIGNATURES 与 28+ 的 SigningInfo） */
    private fun apkSignatureMatches(context: Application, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val archiveInfo = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: return false
        val newSigs: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= 28) {
            archiveInfo.signingInfo?.apkContentsSigners ?: return false
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.signatures ?: return false
        }
        val installed = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val curSigs: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= 28) {
            installed.signingInfo?.apkContentsSigners ?: return false
        } else {
            @Suppress("DEPRECATION")
            installed.signatures ?: return false
        }
        newSigs.any { n -> curSigs.any { c -> c.toByteArray().contentEquals(n.toByteArray()) } }
    }.getOrDefault(false)
}
