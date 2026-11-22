package com.xdo.app.dl

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HLS(m3u8) 下载器。
 *
 * X 的长视频（如 7 分钟）只提供 HLS 分片流：视频是 CMAF/fMP4（init 段 + 多个 .m4s 分片），
 * 且音视频分离（音频在独立的播放列表里）。直接把 m3u8 当单文件下载会得到「只播前面一段」的损坏文件。
 * 这里分片下载所有视频/音频分片，再用 MediaExtractor + MediaMuxer 合成一个带声 MP4。
 *
 * B01：音频播放列表 URL 现在由 XResolver 挂到 QualityOption.audioUrl 传进来；
 *      传入的是媒体（子）播放列表时直接采用，避免丢音轨。
 * B18：所有网络调用可取消，暂停/取消能立刻掐断在途请求（含阻塞中的响应体读取）。
 *
 * P-03（下载提速）：
 * - 分片并发下载：视频轨 + 音频轨共用一个 [SEGMENT_CONCURRENCY] 路的并发许可，两条轨同时下；
 * - 流式落盘：每个分片边读边写到独立的分片文件（不再 body.bytes() 整片读进内存），
 *   全部完成后按序零拷贝拼接（FileChannel.transferTo），拼一个删一个，峰值磁盘占用≈单轨大小；
 * - 单片重试：网络错误 / 超时 / 408 / 429 / 5xx 按指数退避重试 [MAX_ATTEMPTS] 次，
 *   不再因为一个分片抖动就让整个下载失败；403/404 等确定性错误立即失败；
 * - 分片读超时缩短为 [SEGMENT_READ_TIMEOUT_S]，卡死的连接尽快进入重试，而不是干等 120s。
 *
 * P-04（断点续传）：
 * - 分片目录在暂停/失败后保留，目录里的 manifest.txt 记录本次的 init + 分片 URL 清单；
 * - 继续下载时清单一致则跳过已完成分片（分片只有校验完整后才从 .tmp 改名，存在即完整），
 *   清单变化（换清晰度/播放列表变了）则清空重下；两条轨都下完后才拼接，暂停不会丢已下分片。
 *
 * P-05（合成修复）：
 * - 合成结果分三档：音视频完整 / 只有画面 / 失败；「只有画面」不再伪装成功，
 *   通过 [HlsResult.audioMissing] 上报，由调用方提示用户；
 * - 样本缓冲区按轨道 KEY_MAX_INPUT_SIZE 分配，readSampleData 报「缓冲区太小」时自动扩容重读；
 * - 音视频样本按时间戳交错写入，产物不再是「整段视频 + 整段音频」的非交错 MP4。
 */
object HlsDownloader {

    // N-01：与解析、MP4 下载统一（定义在 Net）
    private const val UA = com.xdo.app.net.Net.BROWSER_UA
    private const val TAG = "HlsDownloader"

    /** 单个 HLS 任务同时在途的分片请求数（视频+音频合计）。分片同属一个 CDN 主机，4 路足以跑满带宽 */
    internal const val SEGMENT_CONCURRENCY = 4

    /** 单个分片最多尝试次数（含首次） */
    internal const val MAX_ATTEMPTS = 3

    /** 重试退避基数：600ms、1.2s… */
    private const val RETRY_BASE_DELAY_MS = 600L

    /** 分片读超时：分片只有几百 KB~几 MB，30s 没数据基本就是死连接 */
    private const val SEGMENT_READ_TIMEOUT_S = 30L

    /** 样本缓冲区：轨道未声明 KEY_MAX_INPUT_SIZE 时的初始大小 */
    private const val DEFAULT_SAMPLE_BUF = 2 * 1024 * 1024

    /** 样本缓冲区上限（单个压缩样本不可能超过它；防止异常文件导致无限扩容） */
    private const val MAX_SAMPLE_BUF = 64 * 1024 * 1024

    /** sourceKey 文件第二行：上次产物是否缺音轨（R4 复用时原样上报，不会把无声产物当完整的） */
    private const val SILENT_MARK = "audioMissing"

    /**
     * HLS 下载结果。
     * @property audioMissing 播放列表里有音轨，但最终文件没能带上声音（音频解析/合成失败，只保存了画面）
     */
    data class HlsResult(val file: File, val audioMissing: Boolean)

    /** 合成结果：OK=音视频完整；VIDEO_ONLY=只写进了画面；FAILED=合成失败 */
    internal enum class MuxOutcome { OK, VIDEO_ONLY, FAILED }

    /**
     * 下载并合成 HLS 为单个 MP4 文件。
     * @param client    复用下载器的 OkHttp（共享连接池/调度器；分片读超时在内部单独收紧）
     * @param tmpDir   缓存目录，用于存放中间文件
     * @param masterUrl 主播放列表或媒体播放列表 URL
     * @param audioUrl  音频播放列表 URL（传入媒体播放列表时必需；master 会自行解析音频，可为空）
     * @param onProgress 0..100 进度回调（分片级，单调递增，串行调用；P-01：可挂起，调用方可直接写库）
     * @return 合成结果（文件 + 是否缺音轨）；下载失败抛异常
     */
    suspend fun download(
        client: OkHttpClient,
        tmpDir: File,
        masterUrl: String,
        audioUrl: String? = null,
        onProgress: suspend (Int) -> Unit,
    ): HlsResult {
        tmpDir.mkdirs()
        val videoFile = File(tmpDir, "hls_video.mp4")
        val audioFile = File(tmpDir, "hls_audio.mp4")
        val outFile = File(tmpDir, "hls_muxed.mp4")
        val sourceKeyFile = File(tmpDir, "hls_source.txt")
        val sourceKey = "$masterUrl|${audioUrl.orEmpty()}"

        // R4：MediaSaver 保存失败后重试时，直接复用上一次已合成好的 MP4，
        // 不再把整段 HLS 重新下载一遍。sourceKey 防止换清晰度后误用旧产物。
        if (outFile.exists() && outFile.length() > 0 && sourceKeyFile.exists()) {
            val lines = runCatching { sourceKeyFile.readLines() }.getOrNull().orEmpty()
            if (lines.firstOrNull() == sourceKey) {
                onProgress(100)
                return HlsResult(outFile, audioMissing = lines.getOrNull(1) == SILENT_MARK)
            }
        }

        listOf(videoFile, audioFile, outFile).forEach { it.delete() }
        sourceKeyFile.delete()
        // P-04：分片目录不再无条件清空——暂停/失败后已下完的分片保留，继续时跳过
        val vSegDir = File(tmpDir, "segs_v")
        val aSegDir = File(tmpDir, "segs_a")

        // 分片专用客户端：共享连接池与调度器，只收紧读超时
        val segClient = client.newBuilder()
            .readTimeout(SEGMENT_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        // 1) 取主播放列表，选出最高清视频变体 + 音频变体
        val master = fetchTextWithRetry(segClient, masterUrl)
        val isMaster = master.contains("#EXT-X-STREAM-INF")
        val (videoPlaylist, audioFromMaster) = pickPlaylists(master, masterUrl)
        // B01：master 自带音频就用 master 的；传入的是子播放列表则用调用方传来的音频 URL
        val audioPlaylist = audioFromMaster ?: audioUrl?.takeIf { !isMaster }

        // 2) 并行取视频/音频媒体播放列表（init + 分片）
        val (vMedia, aMedia) = coroutineScope {
            val v = async { fetchTextWithRetry(segClient, videoPlaylist) }
            val a = audioPlaylist?.let { async { fetchTextWithRetry(segClient, it) } }
            v.await() to a?.await()
        }
        ensureNotEncrypted(vMedia)
        aMedia?.let { ensureNotEncrypted(it) }

        val (vInit, vSegs) = parseMediaPlaylist(vMedia, videoPlaylist)
        Log.i(TAG, "视频分片数: ${vSegs.size}")
        var aInit: String? = null
        var aSegs: List<String> = emptyList()
        if (aMedia != null && audioPlaylist != null) {
            val (ai, as_) = parseMediaPlaylist(aMedia, audioPlaylist)
            aInit = ai
            aSegs = as_
            Log.i(TAG, "音频分片数: ${aSegs.size}")
        }
        if (vSegs.isEmpty()) throw IOException("HLS 播放列表没有视频分片")

        // 3) P-04：核对分片清单，统计已完成分片（清单变化则清空重下）
        val vExisting = prepareTrackDir(vSegDir, vInit, vSegs)
        val aExisting = if (aSegs.isNotEmpty()) {
            prepareTrackDir(aSegDir, aInit, aSegs)
        } else {
            aSegDir.deleteRecursively(); 0
        }
        if (vExisting + aExisting > 0) Log.i(TAG, "续传：已有 ${vExisting + aExisting} 个分片，跳过")

        // 进度：分片并发完成，计数用原子量；回调用 Mutex 串行化并保证单调递增
        val total = vSegs.size + aSegs.size + 1 // +1 为合成步骤
        val done = AtomicInteger(vExisting + aExisting)
        val progressLock = Mutex()
        var lastPct = -1
        val onSegmentDone: suspend () -> Unit = {
            val pct = (done.incrementAndGet() * 100 / total).coerceIn(0, 99)
            progressLock.withLock {
                if (pct > lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }
        }
        // 续传时立刻把进度跳到已完成的位置，而不是从 0% 爬
        if (done.get() > 0) {
            lastPct = (done.get() * 100 / total).coerceIn(0, 99)
            onProgress(lastPct)
        }

        // 4) 视频轨与音频轨同时下载，共用并发许可；任一分片重试耗尽即整体失败并取消其余请求
        val permits = Semaphore(SEGMENT_CONCURRENCY)
        coroutineScope {
            launch { downloadSegments(segClient, permits, vInit, vSegs, vSegDir, onSegmentDone) }
            if (aSegs.isNotEmpty()) {
                launch { downloadSegments(segClient, permits, aInit, aSegs, aSegDir, onSegmentDone) }
            }
        }

        // 5) 两条轨都下完后再拼接（拼接是本地操作、很快；放在最后，下载阶段暂停不会丢分片）
        concatTrack(vSegDir, vInit != null, vSegs.size, videoFile)
        if (aSegs.isNotEmpty()) concatTrack(aSegDir, aInit != null, aSegs.size, audioFile)

        // 6) 合成
        currentCoroutineContext().ensureActive()
        val audioExpected = aSegs.isNotEmpty()
        var outcome = mux(videoFile, if (audioExpected) audioFile else null, outFile)
        if (outcome == MuxOutcome.FAILED && audioExpected) {
            // 音视频一起合成失败（多半是音频样本异常）：退一步只合成画面，至少得到标准 MP4
            Log.w(TAG, "音视频合成失败，改为只合成画面")
            outcome = if (mux(videoFile, null, outFile) == MuxOutcome.OK) {
                MuxOutcome.VIDEO_ONLY
            } else {
                MuxOutcome.FAILED
            }
        }
        if (outcome == MuxOutcome.FAILED) {
            // 最后兜底：直接保留拼接好的视频 fMP4（播放器可播），并如实上报
            Log.w(TAG, "合成失败，保留原始视频轨")
            if (outFile.exists()) outFile.delete()
            videoFile.copyTo(outFile, overwrite = true)
        }
        // 只有「播放列表有音轨但没写进去」才算缺音轨；本来就没有单独音轨的流不误报
        val audioMissing = audioExpected && outcome != MuxOutcome.OK
        if (audioMissing) Log.w(TAG, "产物缺少音轨（outcome=$outcome）")
        onProgress(100)
        // 合成完成后中间轨文件不再需要，尽早释放空间
        videoFile.delete()
        audioFile.delete()
        runCatching {
            sourceKeyFile.writeText(if (audioMissing) "$sourceKey\n$SILENT_MARK" else sourceKey)
        }
        return HlsResult(outFile, audioMissing)
    }

    private fun ensureNotEncrypted(media: String) {
        if (media.contains("#EXT-X-KEY:METHOD=") && !media.contains("#EXT-X-KEY:METHOD=NONE")) {
            throw UnsupportedOperationException("该视频已加密，暂不支持下载")
        }
    }

    // ===== 播放列表解析 =====

    /** 从主播放列表选出视频播放列表与音频播放列表 URL */
    internal fun pickPlaylists(master: String, base: String): Pair<String, String?> {
        val lines = master.split("\n").map { it.trim() }
        // 若没有 STREAM-INF，说明本身就是媒体播放列表
        if (!lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            return base to null
        }
        var bestVideo: String? = null
        var bestBw = -1
        val audioUris = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (bw > bestBw) {
                    // URI 在紧随其后的非注释行
                    var j = i + 1
                    while (j < lines.size && !lines[j].startsWith("#")) {
                        bestVideo = resolveUri(base, lines[j]); j++
                        break
                    }
                    bestBw = bw
                }
            } else if (line.startsWith("#EXT-X-MEDIA")) {
                if (line.contains("TYPE=AUDIO", ignoreCase = true)) {
                    Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                        ?.let { audioUris.add(resolveUri(base, it)) }
                }
            }
            i++
        }
        // 选最高码率音频（GROUP-ID 里数字最大）
        val audio = audioUris.maxByOrNull { url ->
            Regex("""audio-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        return (bestVideo ?: base) to audio
    }

    /** 解析媒体播放列表，返回 (init 段 URI 或 null, 分片 URI 列表) */
    internal fun parseMediaPlaylist(body: String, base: String): Pair<String?, List<String>> {
        val lines = body.split("\n").map { it.trim() }
        var init: String? = null
        val segs = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                        ?.let { init = resolveUri(base, it) }
                }
                line.startsWith("#EXTINF") -> {
                    // URI 可能在逗号后的同一行，或在下一行
                    val inline = line.substringAfter(",", "").trim()
                    if (inline.isNotBlank() && !inline.startsWith("#")) {
                        segs.add(resolveUri(base, inline))
                    } else {
                        var j = i + 1
                        while (j < lines.size && lines[j].startsWith("#")) j++
                        if (j < lines.size && lines[j].isNotBlank()) {
                            segs.add(resolveUri(base, lines[j]))
                        }
                        i = j
                    }
                }
            }
            i++
        }
        return init to segs
    }

    internal fun resolveUri(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        if (uri.startsWith("/")) {
            val m = Regex("""(https?://[^/]+)""").find(base)
            return (m?.groupValues?.get(1) ?: "") + uri
        }
        val slash = base.lastIndexOf('/')
        return if (slash >= 0) base.substring(0, slash + 1) + uri else uri
    }

    // ===== 分片下载 =====

    private const val MANIFEST = "manifest.txt"

    internal fun segFile(segDir: File, index: Int) = File(segDir, "seg_$index.seg")

    internal fun initFile(segDir: File) = File(segDir, "init.seg")

    /** 分片只有在校验完整后才会从 .tmp 改名为正式文件名，存在且非空即视为完整 */
    private fun isComplete(f: File) = f.isFile && f.length() > 0

    /**
     * P-04：准备一条轨道的分片目录，返回已完成（可跳过）的分片数（不含 init 段）。
     * - manifest 与本次清单一致：保留已下分片，清掉中断时残留的 .tmp；
     * - 不一致或不存在：清空目录，写入新 manifest，返回 0。
     */
    internal fun prepareTrackDir(segDir: File, init: String?, segs: List<String>): Int {
        val manifest = buildString {
            append(init.orEmpty()).append('\n')
            segs.forEach { append(it).append('\n') }
        }
        val mf = File(segDir, MANIFEST)
        val same = mf.isFile && runCatching { mf.readText() }.getOrNull() == manifest
        if (!same) {
            segDir.deleteRecursively()
            segDir.mkdirs()
            mf.writeText(manifest)
            return 0
        }
        segDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        return segs.indices.count { isComplete(segFile(segDir, it)) }
    }

    /**
     * 并发下载一条轨道的 init 段与分片到各自的文件；已完成的分片直接跳过（P-04）。
     * 每个新下完的分片回调一次 [onOne]（init 段不计入进度）。失败/取消时分片目录保留，供续传。
     */
    private suspend fun downloadSegments(
        client: OkHttpClient,
        permits: Semaphore,
        init: String?,
        segs: List<String>,
        segDir: File,
        onOne: suspend () -> Unit,
    ) {
        segDir.mkdirs()
        coroutineScope {
            if (init != null) {
                val f = initFile(segDir)
                if (!isComplete(f)) launch { permits.withPermit { fetchToFileWithRetry(client, init, f) } }
            }
            segs.forEachIndexed { i, url ->
                val f = segFile(segDir, i)
                if (isComplete(f)) return@forEachIndexed // 续传：已完成分片已计入进度，跳过
                launch {
                    permits.withPermit { fetchToFileWithRetry(client, url, f) }
                    onOne()
                }
            }
        }
    }

    /** 按序零拷贝拼接到 [out]；拼一个删一个，峰值磁盘占用 ≈ 单轨大小 + 一个分片。成功后删除分片目录 */
    private suspend fun concatTrack(segDir: File, hasInit: Boolean, count: Int, out: File) {
        FileOutputStream(out).channel.use { dst ->
            if (hasInit) appendFile(initFile(segDir), dst)
            for (i in 0 until count) {
                currentCoroutineContext().ensureActive()
                appendFile(segFile(segDir, i), dst)
            }
        }
        segDir.deleteRecursively()
    }

    private fun appendFile(src: File, dst: FileChannel) {
        FileInputStream(src).channel.use { ch ->
            val size = ch.size()
            var pos = 0L
            while (pos < size) {
                pos += ch.transferTo(pos, size - pos, dst)
            }
        }
        src.delete()
    }

    /** 分片 HTTP 错误（不继承 IOException，保持「分片下载失败 HTTP xxx」原文提示给用户） */
    internal class SegmentHttpException(val code: Int) : Exception("分片下载失败 HTTP $code")

    /** 是否值得重试：网络抖动/超时/限流/服务端错误重试；4xx（除 408/429）与磁盘满直接失败 */
    internal fun isRetryable(e: Throwable): Boolean = when (e) {
        is SegmentHttpException -> e.code == 408 || e.code == 429 || e.code in 500..599
        is IOException -> {
            val msg = e.message.orEmpty()
            !(msg.contains("ENOSPC", true) || msg.contains("No space left", true))
        }
        else -> false
    }

    private suspend fun <T> withRetry(what: String, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 被暂停/取消时 call.cancel() 会让 read 抛 IOException("Canceled")，此处转为取消而非失败/重试
                currentCoroutineContext().ensureActive()
                attempt++
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) throw e
                Log.w(TAG, "$what 失败（第 $attempt 次），稍后重试: ${e.message}")
                delay(RETRY_BASE_DELAY_MS shl (attempt - 1))
            }
        }
    }

    private suspend fun fetchTextWithRetry(client: OkHttpClient, url: String): String =
        withRetry("播放列表") { fetchText(client, url) }

    private suspend fun fetchToFileWithRetry(client: OkHttpClient, url: String, dest: File) =
        withRetry("分片") { fetchToFile(client, url, dest) }

    private suspend fun fetchText(client: OkHttpClient, url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        val call = client.newCall(req)
        return cancelCallOnAbort(call) {
            callAwait(call).use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 408 || resp.code == 429 || resp.code >= 500) {
                        throw SegmentHttpException(resp.code)
                    }
                    throw IllegalStateException("播放列表获取失败 HTTP ${resp.code}")
                }
                resp.body?.string() ?: throw IOException("HLS 播放列表为空")
            }
        }
    }

    /**
     * 流式下载单个分片到 [dest]：先写 .tmp，校验字节数后再改名，保证分片文件要么完整要么不存在。
     */
    private suspend fun fetchToFile(client: OkHttpClient, url: String, dest: File) {
        val tmp = File(dest.path + ".tmp")
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        val call = client.newCall(req)
        try {
            cancelCallOnAbort(call) {
                callAwait(call).use { resp ->
                    if (!resp.isSuccessful) throw SegmentHttpException(resp.code)
                    val body = resp.body ?: throw IOException("HLS 分片为空")
                    val expected = body.contentLength()
                    var written = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                output.write(buf, 0, n)
                                written += n
                            }
                        }
                    }
                    if (expected > 0 && written != expected) {
                        throw IOException("HLS 分片不完整：$written / $expected 字节")
                    }
                    if (written == 0L) throw IOException("HLS 分片为空")
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("HLS 分片写入失败")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 读响应体是阻塞 IO，协程取消本身打断不了。这里挂一个「看门」子协程：
     * 外层被暂停/取消时它随之取消并调用 call.cancel()，立刻让阻塞中的 read 抛出，释放连接与流量。
     */
    private suspend fun <T> cancelCallOnAbort(call: Call, block: suspend () -> T): T = coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } finally {
            watcher.cancel() // 正常结束时响应已关闭，cancel 一个已完成的 call 无副作用
        }
    }

    /**
     * B18：用 enqueue + suspendCancellableCoroutine 替代阻塞 execute()，
     * 等待响应头期间协程被取消时调用 call.cancel() 立刻掐断在途请求。
     */
    private suspend fun callAwait(call: Call): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
        }

    // ===== 合成（MediaExtractor + MediaMuxer） =====

    /**
     * 把视频 fMP4 与（可选的）音频 fMP4 合成单个 MP4，样本按时间戳交错写入。
     * @return OK：音视频（或无音频输入时的纯视频）完整写入；
     *         VIDEO_ONLY：给了音频但解析/加轨失败，只写进了画面；
     *         FAILED：合成失败（outFile 内容不可用）
     */
    internal fun mux(videoFile: File, audioFile: File?, outFile: File): MuxOutcome {
        if (!videoFile.exists()) return MuxOutcome.FAILED
        if (outFile.exists()) outFile.delete()

        val ve = MediaExtractor()
        var ae: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            try {
                ve.setDataSource(videoFile.path)
            } catch (e: Exception) {
                Log.e(TAG, "视频解析失败: ${e.message}")
                return MuxOutcome.FAILED
            }
            val vTrack = findTrack(ve, "video/")
            if (vTrack < 0) {
                Log.e(TAG, "视频文件中没有视频轨")
                return MuxOutcome.FAILED
            }
            ve.selectTrack(vTrack)
            val vFormat = ve.getTrackFormat(vTrack)

            // 音频：任何一步失败都降级为「只有画面」，但要如实上报
            var aTrack = -1
            var aFormat: MediaFormat? = null
            var audioLost = false
            if (audioFile != null) {
                if (!audioFile.exists()) {
                    audioLost = true
                } else {
                    try {
                        // 先赋值再 setDataSource：解析抛异常时 finally 也能释放这个 extractor
                        val ex = MediaExtractor()
                        ae = ex
                        ex.setDataSource(audioFile.path)
                        aTrack = findTrack(ex, "audio/")
                        if (aTrack >= 0) {
                            ex.selectTrack(aTrack)
                            aFormat = ex.getTrackFormat(aTrack)
                        } else {
                            Log.w(TAG, "音频文件中没有音频轨")
                            audioLost = true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "音频解析失败，仅保留画面: ${e.message}")
                        audioLost = true
                    }
                }
            }

            val m = try {
                MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (e: Exception) {
                Log.e(TAG, "MediaMuxer 创建失败: ${e.message}")
                return MuxOutcome.FAILED
            }
            muxer = m
            val vIdx = m.addTrack(vFormat)
            var aIdx = -1
            if (aFormat != null) {
                aIdx = try {
                    m.addTrack(aFormat)
                } catch (e: Exception) {
                    // 如容器不支持该音频编码：降级为只有画面
                    Log.w(TAG, "音轨加入失败，仅保留画面: ${e.message}")
                    audioLost = true
                    -1
                }
            }
            m.start()
            started = true

            // 局部 var 先转成 val，避免 lambda 内 smart cast 受限
            val aEx = ae
            val aFmt = aFormat
            val readers = ArrayList<SampleReader>(2)
            readers += SampleReader(ve, vIdx, initialBufSize(vFormat))
            if (aEx != null && aIdx >= 0 && aFmt != null) {
                readers += SampleReader(aEx, aIdx, initialBufSize(aFmt))
            }
            writeInterleaved(readers, m)

            m.stop()
            started = false
            return if (audioLost) MuxOutcome.VIDEO_ONLY else MuxOutcome.OK
        } catch (e: Exception) {
            Log.e(TAG, "合成失败: ${e.javaClass.simpleName}: ${e.message}")
            return MuxOutcome.FAILED
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { ve.release() }
            runCatching { ae?.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    /** 按轨道声明的最大样本大小分配缓冲区；未声明时用 [DEFAULT_SAMPLE_BUF] */
    private fun initialBufSize(format: MediaFormat): Int {
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
        } else 0
        return if (declared > 0) declared.coerceIn(64 * 1024, MAX_SAMPLE_BUF) else DEFAULT_SAMPLE_BUF
    }

    /** 下一个缓冲区大小：翻倍并至少容纳 [needed]，不超过上限 */
    internal fun grownBufSize(current: Int, needed: Long = 0): Int {
        val target = maxOf(current.toLong() * 2, needed + 1024)
        return target.coerceAtMost(MAX_SAMPLE_BUF.toLong()).toInt()
    }

    /**
     * 单轨样本读取器：始终持有「当前样本」，供交错写入时比较时间戳。
     * readSampleData 在缓冲区装不下样本时直接抛 IllegalArgumentException（不会返回更大的 size），
     * 所以旧代码里的「size > capacity 再扩容」永远走不到；这里改为捕获后扩容重读。
     */
    private class SampleReader(
        val extractor: MediaExtractor,
        val muxIndex: Int,
        initialSize: Int,
    ) {
        var buf: ByteBuffer = ByteBuffer.allocateDirect(initialSize)
        val info = android.media.MediaCodec.BufferInfo()
        var eos = false
            private set

        /** 读入当前样本到 [buf]/[info]；没有更多样本时置 [eos] 并返回 false */
        fun readCurrent(): Boolean {
            if (eos) return false
            // API 28+ 可提前知道样本大小，直接扩到够用，免一次异常
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val need = extractor.sampleSize
                if (need > buf.capacity()) buf = ByteBuffer.allocateDirect(grownBufSize(buf.capacity(), need))
            }
            while (true) {
                buf.clear()
                val size = try {
                    extractor.readSampleData(buf, 0)
                } catch (e: IllegalArgumentException) {
                    if (buf.capacity() >= MAX_SAMPLE_BUF) throw e
                    buf = ByteBuffer.allocateDirect(grownBufSize(buf.capacity()))
                    continue
                }
                val pts = extractor.sampleTime
                if (size < 0 || pts < 0) {
                    eos = true
                    return false
                }
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                info.set(0, size, pts, flags)
                return true
            }
        }
    }

    /**
     * 交错写入：每次写时间戳最小的那条轨的当前样本（音视频按时间穿插，
     * 而不是整段视频后接整段音频）。每条轨内部时间戳顺序不变，A/V 同步关系与原始流一致。
     */
    private fun writeInterleaved(readers: List<SampleReader>, muxer: MediaMuxer) {
        readers.forEach { it.readCurrent() }
        while (true) {
            val next = readers.filter { !it.eos }.minByOrNull { it.info.presentationTimeUs } ?: break
            muxer.writeSampleData(next.muxIndex, next.buf, next.info)
            next.extractor.advance()
            next.readCurrent()
        }
    }
}
