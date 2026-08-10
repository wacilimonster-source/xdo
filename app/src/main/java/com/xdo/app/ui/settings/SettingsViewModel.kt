package com.xdo.app.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xdo.app.BuildConfig
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.update.UpdateChecker
import com.xdo.app.update.UpdateInfo
import com.xdo.app.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefsHolder.get(app)

    private val _defaultQualityIndex = MutableStateFlow(prefs.defaultQualityIndex)
    val defaultQualityIndex: StateFlow<Int> = _defaultQualityIndex.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.wifiOnly)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _snack = MutableStateFlow<String?>(null)
    val snack: StateFlow<String?> = _snack.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun consumeSnack() {
        _snack.value = null
    }

    fun setDefaultQuality(index: Int) {
        prefs.defaultQualityIndex = index
        _defaultQualityIndex.value = index
    }

    fun setWifiOnly(value: Boolean) {
        prefs.wifiOnly = value
        _wifiOnly.value = value
    }

    fun clearHistory(onCleared: () -> Unit) {
        viewModelScope.launch {
            com.xdo.app.dl.AppHolder.downloadManager?.clearHistory()
            _snack.value = "历史已清空"
            onCleared()
        }
    }

    fun resetUpdate() {
        _updateState.value = UpdateState.Idle
    }

    fun checkUpdate(force: Boolean = false) {
        if (_updateState.value == UpdateState.Checking) return
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            _updateState.value = UpdateChecker.check()
        }
    }

    fun downloadAndInstall(info: UpdateInfo) {
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(0)
            val apk = withContext(Dispatchers.IO) {
                downloadApk(info.apkUrl) { pct ->
                    _updateState.value = UpdateState.Downloading(pct)
                }
            }
            if (apk == null) {
                _updateState.value = UpdateState.DownloadFailed("下载失败，请检查网络")
                return@launch
            }
            installApk(apk)
            _updateState.value = UpdateState.Idle
        }
    }

    private suspend fun downloadApk(url: String, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful || resp.body == null) return@use null
                    val body = resp.body!!
                    val total = body.contentLength()
                    val dir = File(getApplication<Application>().cacheDir, "xdo")
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
                                if (total > 0) {
                                    onProgress((written * 100 / total).toInt().coerceIn(0, 99))
                                }
                            }
                        }
                    }
                    target
                }
            }.getOrNull()
        }

    private fun installApk(apk: File) {
        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    val versionName: String = BuildConfig.VERSION_NAME

    companion object {
        fun navigateToAppDetail(context: android.content.Context) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { context.startActivity(intent) }
        }
    }
}
