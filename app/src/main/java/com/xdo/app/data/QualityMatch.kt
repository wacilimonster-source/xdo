package com.xdo.app.data

/**
 * 旧版本（v0.2.8 及更早）的 variantsJson 没有 audioUrl。
 * 重新解析后，用这个纯函数找回用户原先选择的清晰度：
 * 1) URL 完全一致（最可靠）；
 * 2) label 完全一致（URL 可能带新 token）；
 * 3) 旧记录是「HLS 流」或没有 label → 取最高 HLS；
 * 4) 清晰度档位前缀一致（例如旧 720p HLS 现在只有 720p MP4）。
 */
object QualityMatch {

    fun pick(
        fresh: List<QualityOption>,
        chosenLabel: String?,
        chosenUrl: String?,
    ): QualityOption? {
        if (fresh.isEmpty()) return null
        val label = chosenLabel.orEmpty()
        val url = chosenUrl.orEmpty()

        if (url.isNotBlank()) {
            fresh.firstOrNull { it.url == url }?.let { return it }
        }
        if (label.isNotBlank()) {
            fresh.firstOrNull { it.label == label }?.let { return it }
        }
        if (label.isBlank() || label == "HLS 流") {
            fresh.firstOrNull { it.isHls }?.let { return it }
        }
        val prefix = label.substringBefore(" · ").trim()
        if (prefix.isNotBlank()) {
            fresh.firstOrNull { it.label.startsWith(prefix) }?.let { return it }
        }
        return null
    }
}
