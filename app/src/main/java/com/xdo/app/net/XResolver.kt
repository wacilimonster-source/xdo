package com.xdo.app.net

import com.xdo.app.data.QualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class ResolveResult {
    data class Success(
        val authorName: String,
        val handle: String,
        val text: String,
        val posterUrl: String?,
        val durationMs: Long?,
        val qualities: List<QualityOption>,
    ) : ResolveResult()

    data class Failure(val message: String) : ResolveResult()
}

object XResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private const val SYNDICATION_URL =
        "https://cdn.syndication.twimg.com/tweet-result?id=%s&lang=en&token=%d"

    suspend fun resolve(tweetId: String): ResolveResult = withContext(Dispatchers.IO) {
        try {
            val url = SYNDICATION_URL.format(tweetId, (10000..99999).random())
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ResolveResult.Failure("解析服务返回 ${resp.code}")
                }
                val json = JSONObject(resp.body!!.string())
                if (json.has("error")) {
                    return@withContext ResolveResult.Failure(json.optString("error"))
                }
                parse(json)
            }
        } catch (e: Exception) {
            ResolveResult.Failure(e.message ?: "网络异常")
        }
    }

    private fun parse(json: JSONObject): ResolveResult {
        val user = json.optJSONObject("user")
        val authorName = user?.optString("name", "未知用户") ?: "未知用户"
        val handle = user?.optString("screen_name", "") ?: ""
        val text = json.optString("text", "").take(120)
        val video = json.optJSONObject("video")
            ?: return ResolveResult.Failure("该推文没有视频")
        val posterUrl = video.optString("poster").takeIf { it.isNotBlank() }
        val durationMs = video.optLong("durationMs").takeIf { it > 0 }
        val variants = video.optJSONArray("variants")
            ?: return ResolveResult.Failure("该推文视频暂不支持")

        val qualities = parseVariants(variants)
        if (qualities.isEmpty()) return ResolveResult.Failure("未找到可下载的视频源")

        return ResolveResult.Success(
            authorName = authorName,
            handle = handle,
            text = text,
            posterUrl = posterUrl,
            durationMs = durationMs,
            qualities = qualities,
        )
    }

    private fun parseVariants(variants: JSONArray): List<QualityOption> {
        val list = ArrayList<QualityOption>(variants.length())
        val seen = HashSet<String>()
        for (i in 0 until variants.length()) {
            val v = variants.optJSONObject(i) ?: continue
            // 兼容新旧两种字段命名：新接口 type/src，旧接口 content_type/url
            val type = v.optString("type", v.optString("content_type"))
            if (type != "video/mp4") continue
            val url = v.optString("src", v.optString("url"))
            if (url.isBlank() || !seen.add(url)) continue
            val bitrate = v.optInt("bitrate", 0)
            val dims = parseDims(url)
            list.add(
                QualityOption(
                    label = qualityLabel(url, bitrate),
                    width = dims?.first ?: 0,
                    height = dims?.second ?: 0,
                    bitrateKbps = bitrate / 1000,
                    url = url,
                )
            )
        }
        // 按分辨率（宽高积）降序，码率作次级排序；
        // 新接口不返回 bitrate，旧排序会全部为 0 导致顺序随机，故改为按分辨率排。
        list.sortWith(
            compareByDescending<QualityOption> { it.width * it.height }
                .thenByDescending { it.bitrateKbps }
        )
        return list
    }

    /**
     * 从 URL 提取宽高，兼容两种路径格式：
     *   旧：.../vid/480x270/xxx.mp4
     *   新：.../vid/avc1/320x568/xxx.mp4   （2026 起多了一层编码器目录）
     */
    private fun parseDims(url: String): Pair<Int, Int>? {
        val dims = Regex("""/(\d{3,4})x(\d{3,4})/""").find(url) ?: return null
        return dims.groupValues[1].toInt() to dims.groupValues[2].toInt()
    }

    /** 从 URL 提取分辨率，如 vid/avc1/1080x1920/ → "1080p"；无分辨率时退回码率 */
    fun qualityLabel(url: String, bitrate: Int): String {
        val dims = parseDims(url)
        if (dims != null) {
            val w = dims.first
            val h = dims.second
            // 短边定档：横屏 1280x720 → 720p；竖屏 720x1280 → 720p
            val p = when (minOf(w, h)) {
                in 1440..Int.MAX_VALUE -> "1440p"
                in 1080..1439 -> "1080p"
                in 720..1079 -> "720p"
                in 480..719 -> "480p"
                in 320..479 -> "360p"
                else -> "240p"
            }
            return "$p · ${w}x${h}"
        }
        return if (bitrate > 0) "${bitrate / 1000}kbps" else "视频"
    }
}