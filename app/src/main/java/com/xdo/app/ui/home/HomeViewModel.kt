package com.xdo.app.ui.home

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xdo.app.AppEvents
import com.xdo.app.XDoApp
import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.RecordStatus
import com.xdo.app.dl.AppHolder
import com.xdo.app.net.ResolveResult
import com.xdo.app.net.XResolver
import com.xdo.app.util.TweetLink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as XDoApp).db.recordDao()

    val records: StateFlow<List<DownloadRecord>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pendingResolve = MutableStateFlow<Long?>(null)
    val pendingResolve: StateFlow<Long?> = _pendingResolve

    private val _snack = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snack: MutableSharedFlow<String> = _snack

    private val _clipboardLink = MutableStateFlow<String?>(null)
    val clipboardLink: StateFlow<String?> = _clipboardLink

    fun clearPending() {
        _pendingResolve.value = null
    }

    fun checkClipboard() {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip()) return
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        if (TweetLink.looksLikeTweetText(text)) {
            _clipboardLink.value = text
        }
    }

    fun dismissClipboard() {
        _clipboardLink.value = null
    }

    /** 分享/粘贴进来的文本 → 解析并跳转解析页 */
    fun onShare(text: String) {
        viewModelScope.launch {
            val tweetId = TweetLink.extractTweetId(text)
            if (tweetId == null) {
                _snack.emit("未识别到 X 推文链接")
                return@launch
            }
            val canonicalUrl = "https://x.com/i/status/$tweetId"
            var record = dao.findBySourceUrl(canonicalUrl)
            if (record == null) {
                record = DownloadRecord(
                    sourceUrl = canonicalUrl,
                    tweetId = tweetId,
                    authorName = "",
                    handle = "",
                    text = "",
                    posterUrl = null,
                    durationMs = null,
                    variantsJson = "[]",
                    chosenLabel = null,
                    chosenUrl = null,
                    status = RecordStatus.PARSING,
                    progress = 0,
                    fileUri = null,
                    fileSize = null,
                    errorMsg = null,
                    positionMs = 0,
                    createdAt = System.currentTimeMillis(),
                    completedAt = null,
                )
                record = record.copy(id = dao.upsert(record))
            }
            _pendingResolve.value = record.id
            resolve(record)
        }
    }

    fun paste(text: String) {
        _clipboardLink.value = null
        onShare(text)
    }

    /** 解析推文元数据（幂等：并发保护由调用方保证） */
    fun resolve(record: DownloadRecord) {
        viewModelScope.launch {
            dao.upsert(record.copy(status = RecordStatus.PARSING, errorMsg = null))
            val result = XResolver.resolve(record.tweetId)
            when (result) {
                is ResolveResult.Success -> {
                    dao.upsert(record.copy(
                        status = RecordStatus.READY,
                        authorName = result.authorName,
                        handle = result.handle,
                        text = result.text,
                        posterUrl = result.posterUrl,
                        durationMs = result.durationMs,
                        variantsJson = encodeVariants(result.qualities),
                        errorMsg = null,
                    ))
                }
                is ResolveResult.Failure -> {
                    dao.upsert(record.copy(
                        status = RecordStatus.FAILED,
                        errorMsg = result.message,
                    ))
                }
            }
        }
    }

    fun retryResolve(record: DownloadRecord) {
        resolve(record)
    }

    fun cancel(record: DownloadRecord) {
        AppHolder.downloadManager?.cancelDownload(record.id)
    }

    fun pause(record: DownloadRecord) {
        AppHolder.downloadManager?.pauseDownload(record.id)
    }

    fun startOrResume(record: DownloadRecord, force: Boolean = false) {
        AppHolder.downloadManager?.startDownload(record.id, force)
    }

    fun deleteRecord(record: DownloadRecord) {
        AppHolder.downloadManager?.deleteRecord(record.id)
    }

    fun clearHistory() {
        AppHolder.downloadManager?.clearHistory()
    }

    fun copyLink(record: DownloadRecord) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("tweet", record.sourceUrl))
        viewModelScope.launch { _snack.emit("链接已复制") }
    }

    fun shareFile(record: DownloadRecord) {
        val uri = record.fileUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            clipData = android.content.ClipData.newRawUri("video", Uri.parse(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val context = getApplication<Application>()
        val chooser = Intent.createChooser(intent, "分享视频").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun encodeVariants(qualities: List<com.xdo.app.data.QualityOption>): String {
        val arr = JSONArray()
        qualities.forEach { q ->
            arr.put(JSONObject().apply {
                put("label", q.label)
                put("url", q.url)
                put("bitrate", q.bitrateKbps)
                put("isHls", q.isHls)
            })
        }
        return arr.toString()
    }
}

fun decodeVariants(json: String): List<com.xdo.app.data.QualityOption> {
    val list = ArrayList<com.xdo.app.data.QualityOption>()
    runCatching {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                com.xdo.app.data.QualityOption(
                    label = o.getString("label"),
                    width = 0,
                    height = 0,
                    bitrateKbps = o.optInt("bitrate", 0),
                    url = o.getString("url"),
                    isHls = o.optBoolean("isHls", false),
                )
            )
        }
    }
    return list
}