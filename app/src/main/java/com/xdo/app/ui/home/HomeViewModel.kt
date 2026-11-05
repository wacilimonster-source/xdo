package com.xdo.app.ui.home

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xdo.app.AppEvents
import com.xdo.app.XDoApp
import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.RecordStatus
import com.xdo.app.dl.AppHolder
import com.xdo.app.net.RecordResolver
import com.xdo.app.util.TweetLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /** B27：查重+插入的互斥锁，防止并发分享同一新链接插入重复记录 */
    private val shareMutex = Mutex()

    /** 同一记录的去重解析（B27/防抖：解析进行中不再重复发起） */
    private val resolvingIds = HashSet<Long>()

    fun clearPending() {
        _pendingResolve.value = null
    }

    /** 旧记录元数据回填：标题/封面缺失时静默重新解析一次（每次启动最多一次） */
    private var backfillTriggered = false

    fun maybeBackfillMeta() {
        if (backfillTriggered) return
        backfillTriggered = true
        viewModelScope.launch {
            records.first { list ->
                list.firstOrNull { it.needsMetaBackfill() }?.let { rec ->
                    resolve(rec.id)
                    true
                } ?: false
            }
        }
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
            // B27：查重与插入互斥，两个协程不会同时查到 null
            val (record, existed) = shareMutex.withLock {
                val existing = dao.findBySourceUrl(canonicalUrl)
                if (existing != null) {
                    existing to true
                } else {
                    var rec = DownloadRecord(
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
                    rec = rec.copy(id = dao.upsert(rec))
                    rec to false
                }
            }
            when {
                // B02：已完成的记录不再重新解析/重置状态（旧逻辑会把 DONE 打回 READY 并引发重复下载）
                existed && record.status == RecordStatus.DONE ->
                    _snack.emit("该视频已下载过，可在记录中查看")
                existed && record.status == RecordStatus.DOWNLOADING ->
                    _snack.emit("该视频正在下载中")
                existed && record.status == RecordStatus.PAUSED ->
                    _snack.emit("该视频已暂停，可在记录中继续")
                // PARSING：解析已在进行（或启动对账后待重试），只导航不重复发起
                existed && record.status == RecordStatus.PARSING -> _pendingResolve.value = record.id
                else -> {
                    _pendingResolve.value = record.id
                    resolve(record.id)
                }
            }
        }
    }

    fun paste(text: String) {
        _clipboardLink.value = null
        onShare(text)
    }

    /**
     * 解析推文元数据（B02/B12：统一走 RecordResolver，只更新元数据字段，
     * 不覆盖 DONE/DOWNLOADING/PAUSED，不复活已删除记录；同记录去重防并发）。
     */
    fun resolve(recordId: Long) {
        val acquired = synchronized(resolvingIds) { resolvingIds.add(recordId) }
        if (!acquired) return
        viewModelScope.launch {
            try {
                RecordResolver.resolveAndApply(dao, recordId)
            } finally {
                synchronized(resolvingIds) { resolvingIds.remove(recordId) }
            }
        }
    }

    fun retryResolve(record: DownloadRecord) {
        resolve(record.id)
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

    /** B26：文件检查移到 IO 线程（主线程做 ContentResolver I/O 会卡顿） */
    fun isFileDeletedAsync(record: DownloadRecord, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = checkFileDeleted(record)
            withContext(Dispatchers.Main) { onResult(deleted) }
        }
    }

    private fun checkFileDeleted(record: DownloadRecord): Boolean {
        val uri = record.fileUri ?: return true
        return try {
            val u = Uri.parse(uri)
            if (u.scheme == "file") {
                !File(u.path!!).exists()
            } else {
                // content:// URI，通过 ContentResolver 检查
                getApplication<Application>().contentResolver.openInputStream(u)?.close()
                false
            }
        } catch (e: Exception) {
            true
        }
    }

    /** 文件被删后重新下载：把 DONE 状态重置，清除 fileUri，重新发起下载 */
    fun reprepareDownload(record: DownloadRecord) {
        viewModelScope.launch {
            dao.upsert(record.copy(
                status = RecordStatus.READY,
                fileUri = null,
                fileSize = null,
                progress = 0,
                errorMsg = null,
                completedAt = null,
            ))
            AppHolder.downloadManager?.startDownload(record.id, false)
        }
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
}

/** 是否缺少标题或封面需要静默回填（B30：FAILED 不参与，避免每次冷启动白白重解析一次；DONE 已排除） */
private fun DownloadRecord.needsMetaBackfill(): Boolean =
    (text.isBlank() || posterUrl.isNullOrBlank()) && status == RecordStatus.READY
