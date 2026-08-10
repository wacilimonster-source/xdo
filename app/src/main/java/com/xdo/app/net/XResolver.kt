package com.xdo.app.net

import com.xdo.app.data.QualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
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

    // 代理/境外网络下，复用坏连接或服务器「半开」连接会导致 read 永远等不到 EOF。
    // retryOnConnectionFailure(false) 避免把坏连接重试复用；timeout 兜底。
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private const val SYNDICATION_URL =
        "https://cdn.syndication.twimg.com/tweet-result?id=%s&lang=en&token=%d"

    /** 带硬超时的解析：任何卡死都会在 HARD_TIMEOUT 内转为失败，绝不死转圈 */
    private const val HARD_TIMEOUT_MS = 12_000L

    /** 备用通道（fxtwitter）独立短超时，避免拖死整体解析 */
    private const val FALLBACK_TIMEOUT_MS = 6_000L

    /** 主通道结果：Ok=成功；NoVideo=数据完好但无视频（可信，不试备用）；Blocked=被拦截/异常（试备用） */
    private sealed class PrimaryResult {
        data class Ok(val r: ResolveResult.Success) : PrimaryResult()
        object NoVideo : PrimaryResult()
        object Blocked : PrimaryResult()
    }

    /**
     * 解析推文元数据。
     *
     * 双通道策略：
     * 1) 主通道 X 官方 syndication（带可选 Cookie）；
     * 2) 主通道被拦截（tombstone / error / 异常）时，自动切换到免费匿名备用通道
     *    fxtwitter（实测对 syndication 拦截的公开推文可正常返回视频直链）。
     *
     * 注意：synerication 的 tombstone 并不代表「该推文需要登录」——
     * 它也会拦截大量正常公开推文；登录 Cookie 无法改变该行为，故不再优先引导用户登录。
     *
     * @param cookie 可选：X 登录 Cookie（auth_token; ct0 等）
     */
    suspend fun resolve(tweetId: String, cookie: String? = null): ResolveResult =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(HARD_TIMEOUT_MS) {
                    val primary = fetchSyndication(tweetId, cookie)
                    when (primary) {
                        is PrimaryResult.Ok -> return@withTimeout primary.r
                        is PrimaryResult.NoVideo -> {
                            return@withTimeout ResolveResult.Failure("该推文没有视频")
                        }
                        // 通道被拦截：走备用通道
                        PrimaryResult.Blocked -> {
                            val fallback = fetchFxtwitter(tweetId)
                            if (fallback != null) return@withTimeout fallback
                            // 备用通道也失败：给出不误导用户的提示
                            return@withTimeout ResolveResult.Failure(
                                if (cookie.isNullOrBlank())
                                    "该推文暂时无法解析（X 未公开视频数据）。可稍后重试，或登录 X 后重试；仍失败可到浏览器打开确认是否删除/受限"
                                else
                                    "解析仍然失败，登录状态可能已失效，或该推文已删除/受限"
                            )
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                ResolveResult.Failure("解析超时（12s），请检查网络或代理后重试")
            } catch (e: Exception) {
                ResolveResult.Failure(e.message ?: "网络异常")
            }
        }

    /** 主通道：X syndication。tombstone/error/HTTP 失败返回 Blocked（交给备用通道） */
    private suspend fun fetchSyndication(tweetId: String, cookie: String?): PrimaryResult {
        try {
            val url = SYNDICATION_URL.format(tweetId, (10000..99999).random())
            val reqBuilder = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
            if (!cookie.isNullOrBlank()) reqBuilder.header("Cookie", cookie)
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return PrimaryResult.Blocked
                val body = resp.body ?: return PrimaryResult.Blocked
                val text = safeRead(body)
                if (text.isBlank()) return PrimaryResult.Blocked
                val json = JSONObject(text)
                if (json.has("error")) return PrimaryResult.Blocked
                // X 对匿名/受限访问返回 {"__typename":"Tweet","tombstone":{}}，不含内容。
                if (json.has("tombstone")) return PrimaryResult.Blocked
                val video = json.optJSONObject("video")
                    ?: return PrimaryResult.NoVideo
                val posterUrl = video.optString("poster").takeIf { it.isNotBlank() }
                val durationMs = video.optLong("durationMs").takeIf { it > 0 }
                val variants = video.optJSONArray("variants")
                    ?: return PrimaryResult.NoVideo
                val qualities = parseVariants(variants, cookie)
                if (qualities.isEmpty()) return PrimaryResult.NoVideo
                val user = json.optJSONObject("user")
                return PrimaryResult.Ok(
                    ResolveResult.Success(
                        authorName = user?.optString("name", "未知用户") ?: "未知用户",
                        handle = user?.optString("screen_name", "") ?: "",
                        text = json.optString("text", "").take(120),
                        posterUrl = posterUrl,
                        durationMs = durationMs,
                        qualities = qualities,
                    )
                )
            }
        } catch (e: Exception) {
            // 网络类异常视为通道不可用，交给备用通道再试一次
            return PrimaryResult.Blocked
        }
    }

    /** 备用通道内部结果：Found=拿到视频；NoVideo=数据完好但确实无视频；Unavailable=通道不可用 */
    private sealed class FxtOutcome {
        data class Found(val r: ResolveResult.Success) : FxtOutcome()
        object NoVideo : FxtOutcome()
        object Unavailable : FxtOutcome()
    }

    /**
     * 备用通道：fxtwitter（免登录、免 Key 的第三方聚合）。
     * 返回 null 表示该通道也不可用；「确实无视频」时返回 Failure。
     */
    private suspend fun fetchFxtwitter(tweetId: String): ResolveResult? {
        val outcome: FxtOutcome = try {
            withTimeout(FALLBACK_TIMEOUT_MS) {
                val url = "https://api.fxtwitter.com/status/$tweetId"
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use FxtOutcome.Unavailable
                    val text = safeRead(resp.body ?: return@use FxtOutcome.Unavailable)
                    val json = JSONObject(text)
                    if (json.optInt("code") != 200) return@use FxtOutcome.Unavailable
                    val tweet = json.optJSONObject("tweet") ?: return@use FxtOutcome.Unavailable
                    val media = tweet.optJSONObject("media") ?: return@use FxtOutcome.Unavailable
                    val videos = media.optJSONArray("videos")
                    if (videos == null || videos.length() == 0) {
                        return@use FxtOutcome.NoVideo
                    }
                    val v = videos.getJSONObject(0)
                    val videoUrl = v.optString("url")
                    if (videoUrl.isBlank()) return@use FxtOutcome.Unavailable
                    val isHls = videoUrl.endsWith(".m3u8", ignoreCase = true)
                    val author = tweet.optJSONObject("author")
                    FxtOutcome.Found(
                        ResolveResult.Success(
                            authorName = author?.optString("name") ?: "未知用户",
                            handle = author?.optString("screen_name") ?: "",
                            text = tweet.optString("text").take(120),
                            posterUrl = null,
                            durationMs = (v.optDouble("duration") * 1000).toLong()
                                .takeIf { it > 0 },
                            qualities = listOf(
                                QualityOption(
                                    label = qualityLabel(videoUrl, 0).let {
                                        if (isHls) "$it · HLS" else it
                                    },
                                    width = 0,
                                    height = 0,
                                    bitrateKbps = 0,
                                    url = videoUrl,
                                    isHls = isHls,
                                )
                            ),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            FxtOutcome.Unavailable
        }
        return when (outcome) {
            is FxtOutcome.Found -> outcome.r
            FxtOutcome.NoVideo -> ResolveResult.Failure("该推文没有视频")
            FxtOutcome.Unavailable -> null
        }
    }

    /** 安全读取响应体：避免某些代理返回的 chunked/gzip 流读不到 EOF 时无限阻塞 */
    private fun safeRead(body: ResponseBody): String = try {
        val src = body.source()
        // 最多读 8MB，到上限即停止，防止异常超大响应拖死
        val buf = okio.Buffer()
        val limit = 8L * 1024 * 1024
        var total = 0L
        while (true) {
            val read = src.read(buf, 8192)
            if (read == -1L) break
            total += read
            if (total >= limit) break
        }
        buf.readUtf8()
    } catch (e: Exception) {
        ""
    }

    private fun parseVariants(variants: JSONArray, cookie: String?): List<QualityOption> {
        val list = ArrayList<QualityOption>(variants.length() * 2)
        val seen = HashSet<String>()
        for (i in 0 until variants.length()) {
            val v = variants.optJSONObject(i) ?: continue
            // 兼容新旧两种字段命名：新接口 type/src，旧接口 content_type/url
            val type = v.optString("type", v.optString("content_type"))
            val url = v.optString("src", v.optString("url"))
            if (url.isBlank() || !seen.add(url)) continue
            when (type) {
                "video/mp4" -> {
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
                // HLS 流：长视频（如 7 分钟）X 只提供 m3u8 分片流，需分片下载后合成。
                // 直接下载 m3u8 当单文件会得到「只播前面一段」的损坏文件。
                "application/x-mpegURL" -> list.addAll(parseHlsVariants(url, cookie))
            }
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
     * 解析 HLS 主播放列表，为其中每个清晰度生成一项（带 isHls=true）。
     * 解析失败则兜底返回单个「HLS 流」选项，保证仍可下载。
     */
    private fun parseHlsVariants(masterUrl: String, cookie: String?): List<QualityOption> {
        // HLS 子请求独立短超时，且失败兜底为单个「HLS 流」选项，绝不拖死主解析
        return runCatching {
            val reqBuilder = Request.Builder().url(masterUrl)
                .header("User-Agent", UA)
            if (!cookie.isNullOrBlank()) reqBuilder.header("Cookie", cookie)
            val call = client.newCall(reqBuilder.build())
            // 5s 硬超时，避免代理下 m3u8 拉取卡死
            val resp = kotlinx.coroutines.runBlocking {
                withTimeout(5000) { call.execute() }
            }
            resp.use {
                if (!it.isSuccessful) return@runCatching null
                it.body?.let { b -> safeRead(b) }
            }
        }.getOrNull()
            ?.let { parseMasterPlaylist(it, masterUrl) }
            ?: listOf(
                QualityOption(
                    label = "HLS 流",
                    width = 0, height = 0, bitrateKbps = 0,
                    url = masterUrl, isHls = true,
                )
            )
    }

    /** 解析 HLS 主播放列表（含 #EXT-X-STREAM-INF），返回各清晰度选项；若已是媒体播放列表则回退单选项 */
    private fun parseMasterPlaylist(body: String, masterUrl: String): List<QualityOption> {
        val lines = body.split("\n").map { it.trim() }
        if (!lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            // 已是媒体播放列表：取一个通用 HLS 选项
            val dims = Regex("""(\d{3,4})x(\d{3,4})""").find(masterUrl)?.groupValues
            val (w, h) = if (dims != null) dims[1].toInt() to dims[2].toInt() else 0 to 0
            return listOf(
                QualityOption(
                    label = if (w > 0) "${tierLabel(w, h)} · ${w}x${h} · HLS" else "HLS 流",
                    width = w, height = h, bitrateKbps = 0,
                    url = masterUrl, isHls = true,
                )
            )
        }
        val out = ArrayList<QualityOption>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val res = Regex("""RESOLUTION=(\d{3,4})x(\d{3,4})""").find(line)
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                // URI 可能在下一行（绝对或相对）
                var uri: String? = null
                var j = i + 1
                while (j < lines.size && uri == null) {
                    val n = lines[j]
                    if (n.startsWith("#")) { i = j; break }
                    uri = n; i = j
                    j++
                }
                if (uri != null && res != null) {
                    val w = res.groupValues[1].toInt()
                    val h = res.groupValues[2].toInt()
                    out.add(
                        QualityOption(
                            label = "${tierLabel(w, h)} · ${w}x${h} · HLS",
                            width = w, height = h, bitrateKbps = bw / 1000,
                            url = resolveUri(masterUrl, uri), isHls = true,
                        )
                    )
                }
            }
            i++
        }
        return out.ifEmpty {
            listOf(QualityOption("HLS 流", 0, 0, 0, masterUrl, isHls = true))
        }
    }

    private fun tierLabel(w: Int, h: Int): String = when (minOf(w, h)) {
        in 1440..Int.MAX_VALUE -> "1440p"
        in 1080..1439 -> "1080p"
        in 720..1079 -> "720p"
        in 480..719 -> "480p"
        in 320..479 -> "360p"
        else -> "240p"
    }

    /** 将播放列表中的相对/根相对 URI 解析为绝对 URL */
    private fun resolveUri(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        if (uri.startsWith("/")) {
            val m = Regex("""(https?://[^/]+)""").find(base)
            return (m?.groupValues?.get(1) ?: "") + uri
        }
        val slash = base.lastIndexOf('/')
        return if (slash >= 0) base.substring(0, slash + 1) + uri else uri
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
