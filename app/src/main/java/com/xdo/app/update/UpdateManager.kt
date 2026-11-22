package com.xdo.app.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.net.Net
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

    // N-01：从全局基座派生；未设 UA 的请求由基座补 XDo 应用 UA
    private val client: OkHttpClient = Net.builder()
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
        pendingApk = null
        _state.value = UpdateState.Idle
    }

    @Volatile
    private var downloadJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var downloadCancelled = false

    /** 取消正在进行的更新下载（更新弹窗「取消下载」按钮；之前 reset 只关弹窗，后台协程仍会继续跑） */
    fun cancelDownload() {
        downloadCancelled = true
        downloadJob?.cancel()
        downloadJob = null
        pendingApk = null
        _state.value = UpdateState.Idle
    }

    /** 已下载并通过签名校验、等待「安装未知应用」权限的 APK（权限开启后无需重新下载） */
    @Volatile
    private var pendingApk: File? = null

    /**
     * 「去开启」：跳转系统设置中本应用的「安装未知应用」开关页
     * （Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES 直达该应用的开关，不用用户自己找）。
     */
    fun openInstallPermissionSettings() {
        val context = app()
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * 从系统设置回到应用后（ON_RESUME）调用：权限已开启则直接安装已下载的 APK，
     * 不重新下载；仍未开启则保持 InstallBlocked 等待；APK 已丢失则重新下载。
     */
    fun continueInstall() {
        if (_state.value !is UpdateState.InstallBlocked) return
        val apk = pendingApk
        if (apk == null || !apk.exists()) {
            val info = _lastAvailable.value
            if (info != null) downloadAndInstall(info) else reset()
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !app().packageManager.canRequestPackageInstalls()) return
        pendingApk = null
        _state.value = if (installApk(apk)) UpdateState.Idle
        else UpdateState.DownloadFailed("无法启动安装器，请重试")
    }

    /** 拉起系统安装器；返回是否成功启动 */
    private fun installApk(apk: File): Boolean {
        val context = app()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 下载并安装更新（进度实时写入全局状态，任何页面都可看到） */
    fun downloadAndInstall(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        _lastAvailable.value = info
        downloadCancelled = false
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(0)
            // P-06：原来每读 64KB 就发一次新状态（几 MB 的 APK 就是上百次），弹窗跟着每次重组；
            // 现在只在百分比变化时更新（最多 100 次）
            var lastPct = 0
            val apk = withContext(Dispatchers.IO) {
                downloadApk(info.apkUrl) { bytes, total ->
                    // 取消后立即停止回写状态（协程取消传播可能有延迟）
                    if (downloadCancelled) return@downloadApk
                    val pct = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 99) else 0
                    if (pct == lastPct) return@downloadApk
                    lastPct = pct
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

            // 权限缺失：保留已下载并校验过的 APK，进入引导态（去系统设置开启后自动续装）
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                pendingApk = apk
                _state.value = UpdateState.InstallBlocked("需要允许「安装未知应用」才能安装新版本")
                return@launch
            }

            _state.value = if (installApk(apk)) UpdateState.Idle
            else UpdateState.DownloadFailed("无法启动安装器，请重试")
        }
    }

    private const val TAG = "XdoUpdate"

    private suspend fun downloadApk(url: String, onProgress: (Long, Long) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful || resp.body == null) {
                        Log.w(TAG, "downloadApk: HTTP ${resp.code} url=$url")
                        return@use null
                    }
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
                        if (!complete) {
                            Log.w(TAG, "downloadApk: 不完整 written=$written total=$total")
                            target.delete()
                        }
                        if (complete) target else null
                    }
                }
            }.onFailure { Log.w(TAG, "downloadApk: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
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
