package com.xdo.app.update

import android.app.Application
import com.xdo.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val updateMessage: String,
    val apkUrl: String,
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    object UpToDate : UpdateState()
    object CheckFailed : UpdateState()
    data class Downloading(
        val progress: Int,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    ) : UpdateState()
    data class DownloadFailed(val reason: String) : UpdateState()
}

/** 检查更新：读取仓库 version.txt 并与本地版本比对 */
object UpdateChecker {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/wacilimonster-source/xdo/main/version.txt"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        return@withContext try {
            val req = Request.Builder().url(VERSION_URL).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful || resp.body == null) {
                    UpdateState.CheckFailed
                } else {
                    val json = JSONObject(resp.body!!.string())
                    val info = UpdateInfo(
                        versionCode = json.getInt("versionCode"),
                        versionName = json.getString("versionName"),
                        updateMessage = json.optString("updateMessage", ""),
                        apkUrl = json.getString("apkUrl"),
                    )
                    if (info.versionCode > BuildConfig.VERSION_CODE) {
                        UpdateState.Available(info)
                    } else {
                        UpdateState.UpToDate
                    }
                }
            }
        } catch (e: Exception) {
            UpdateState.CheckFailed
        }
    }
}