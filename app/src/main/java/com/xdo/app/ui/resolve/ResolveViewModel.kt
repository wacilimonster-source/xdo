package com.xdo.app.ui.resolve

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xdo.app.XDoApp
import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.QualityOption
import com.xdo.app.data.RecordStatus
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.ui.home.decodeVariants
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 解析页 VM。
 * record 通过 Flow 订阅，解析完成后状态从 PARSING 自动刷新为 READY/FAILED，
 * 无需手动轮询。
 */
class ResolveViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as XDoApp).db.recordDao()
    private val prefs = AppPrefsHolder.get(app)

    private val _record = MutableStateFlow<DownloadRecord?>(null)
    val record: StateFlow<DownloadRecord?> = _record.asStateFlow()

    private val _qualities = MutableStateFlow<List<QualityOption>>(emptyList())
    val qualities: StateFlow<List<QualityOption>> = _qualities.asStateFlow()

    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var watchJob: Job? = null

    fun consumeMessage() {
        _message.value = null
    }

    fun load(recordId: Long) {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            dao.observeById(recordId).collectLatest { rec ->
                _record.value = rec
                val qs = decodeVariants(rec?.variantsJson ?: "")
                _qualities.value = qs
                if (rec == null) return@collectLatest
                _downloading.value = rec.status == RecordStatus.DOWNLOADING
                val chosenIdx = rec.chosenLabel?.let { label ->
                    qs.indexOfFirst { it.label == label }
                }
                _selectedIndex.value = if (chosenIdx != null && chosenIdx in qs.indices)
                    chosenIdx
                else
                    pickDefaultIndex(qs)
            }
        }
    }

    fun select(index: Int) {
        if (index in _qualities.value.indices) _selectedIndex.value = index
    }

    /** 开始下载；force=true 用于移动网络下强制下载 */
    fun startDownload(force: Boolean = false, onStarted: () -> Unit) {
        val rec = _record.value ?: return
        val qs = _qualities.value
        val idx = _selectedIndex.value
        if (qs.isEmpty() || idx !in qs.indices) {
            _message.value = "暂无可下载的视频源"
            return
        }
        val chosen = qs[idx]
        viewModelScope.launch {
            dao.upsert(rec.copy(
                status = RecordStatus.DOWNLOADING,
                chosenLabel = chosen.label,
                chosenUrl = chosen.url,
                progress = 0,
                errorMsg = null,
            ))
            com.xdo.app.dl.AppHolder.downloadManager?.startDownload(rec.id, force)
            onStarted()
        }
    }

    /** 重新解析（解析失败时使用） */
    fun resolveAgain(recordId: Long) {
        viewModelScope.launch {
            val rec = dao.getById(recordId) ?: return@launch
            dao.upsert(rec.copy(status = RecordStatus.PARSING, errorMsg = null))
            _downloading.value = false
        }
    }

    /** 全局设置中的目标清晰度（与 QUALITY_LABELS 顺序一致）→ 实际索引 */
    private fun pickDefaultIndex(qs: List<QualityOption>): Int {
        if (qs.isEmpty()) return 0
        val saved = prefs.defaultQualityIndex
        if (saved <= 0) return 0 // 智能：最高清（已降序）
        val targets = listOf(0, 1440, 1080, 720, 480, 360, 240)
        val target = targets.getOrElse(saved) { 240 }
        var best = 0
        var bestDiff = Int.MAX_VALUE
        qs.forEachIndexed { i, q ->
            val p = Regex("""(\d{3,4})p""").find(q.label)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (p != null) {
                val diff = kotlin.math.abs(p - target)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = i
                }
            }
        }
        return best
    }
}
