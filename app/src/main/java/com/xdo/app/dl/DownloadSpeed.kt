package com.xdo.app.dl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 下载实时状态（内存态）：速度/剩余时间（H-03）+ 实时进度百分比（P-01）。
 *
 * - DownloadManager 在进度回调中上报，UI 通过 StateFlow 订阅；
 * - P-01：进度不再每 150ms 写一次 Room（整行 REPLACE 会让 observeAll 重查全表、首页整体重组），
 *   实时进度只放内存，Room 仅节流（约 1s）持久化一次，供进程被杀后的对账/暂停显示使用；
 * - 任务结束（完成/暂停/取消/失败）时由 clearProgress 一并清理；进程被杀后自然清空，无需持久化。
 */
object DownloadSpeed {

    data class Info(
        val bytesPerSec: Long = 0L,
        val etaSec: Long? = null,
        /** 实时进度 0..100；null 表示尚未上报，UI 回退到数据库里的 record.progress */
        val progress: Int? = null,
    )

    private val _map = MutableStateFlow<Map<Long, Info>>(emptyMap())
    val state: StateFlow<Map<Long, Info>> = _map.asStateFlow()

    /**
     * P-06：单个任务的实时状态。首页卡片各自订阅自己这一条，
     * 其他任务的网速/进度变化不会让它（更不会让整个首页）重组。
     */
    fun infoFlow(recordId: Long): Flow<Info?> = _map.map { it[recordId] }.distinctUntilChanged()

    fun infoOf(recordId: Long): Info? = _map.value[recordId]

    fun report(recordId: Long, bytesPerSec: Long, etaSec: Long?) {
        if (bytesPerSec <= 0) return
        // update{} 是原子 CAS，两个并发下载同时上报不会互相覆盖
        _map.update { m ->
            val old = m[recordId] ?: Info()
            m + (recordId to old.copy(bytesPerSec = bytesPerSec, etaSec = etaSec?.takeIf { it > 0 }))
        }
    }

    /** P-01：上报实时进度；百分比未变化时不发射，避免无意义重组 */
    fun reportProgress(recordId: Long, progress: Int) {
        _map.update { m ->
            val old = m[recordId] ?: Info()
            if (old.progress == progress) m else m + (recordId to old.copy(progress = progress))
        }
    }

    /** 当前内存中的实时进度（暂停时用于把最新进度落库） */
    fun progressOf(recordId: Long): Int? = _map.value[recordId]?.progress

    fun clear(recordId: Long) {
        _map.update { it - recordId }
    }
}
