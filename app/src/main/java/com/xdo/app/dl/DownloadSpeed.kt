package com.xdo.app.dl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载速度/剩余时间上报（UI 优化 H-03：下载卡片显示「68% · 2.4MB/s · 剩余 8s」）。
 * DownloadManager 在进度回调中报告瞬时速度，UI 通过 StateFlow 订阅；
 * 任务结束（完成/暂停/取消/失败）时由 clearProgress 一并清理。
 * 进程被杀后自然清空，无需持久化。
 */
object DownloadSpeed {

    data class Info(
        val bytesPerSec: Long,
        val etaSec: Long?,
    )

    private val _map = MutableStateFlow<Map<Long, Info>>(emptyMap())
    val state: StateFlow<Map<Long, Info>> = _map.asStateFlow()

    fun report(recordId: Long, bytesPerSec: Long, etaSec: Long?) {
        if (bytesPerSec <= 0) return
        _map.value = _map.value + (recordId to Info(bytesPerSec, etaSec?.takeIf { it > 0 }))
    }

    fun clear(recordId: Long) {
        _map.value = _map.value - recordId
    }
}
