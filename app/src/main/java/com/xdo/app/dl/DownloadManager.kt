package com.xdo.app.dl

import android.content.Context
import android.net.Uri
import com.xdo.app.BuildConfig
import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.RecordDao
import com.xdo.app.data.RecordStatus
import com.xdo.app.data.VariantsJson
import com.xdo.app.net.XResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 下载管理器（Application 单例持有）。
 * - 并发上限 2；断点续传：写 cache/xdo/{id}_{urlHash}.part，带 Range 头续传
 *   （B04：part 与 URL 绑定，换清晰度自动作废旧 part，杜绝混合字节产出损坏视频）
 * - 完成后经 MediaSaver 写入媒体库，返回 content:// URI 存入记录
 * - 状态/进度持续写 Room（UI 通过 Flow 观察），进度同步到前台服务通知
 */
class DownloadManager(
    private val context: Context,
    private val dao: RecordDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val jobs = HashMap<Long, Job>()

    /** 活跃任务的进度快照（recordId -> 标题, 百分比），供前台服务聚合渲染（B28） */
    private val progressMap = LinkedHashMap<Long, Pair<String, Int>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val UA = "XDo/${BuildConfig.VERSION_NAME}"

    fun shutdown() {
        synchronized(jobs) { jobs.values.forEach { it.cancel() }; jobs.clear() }
        scope.cancel()
    }

    fun isIdle(): Boolean = synchronized(jobs) { jobs.isEmpty() }

    /** 除 recordId 外是否还有活跃任务（B10：并发下载时任一任务完成不应停掉服务） */
    fun hasActiveExcept(recordId: Long): Boolean =
        synchronized(jobs) { jobs.keys.any { it != recordId } }

    fun progressSnapshot(): List<Triple<Long, String, Int>> =
        synchronized(progressMap) { progressMap.map { Triple(it.key, it.value.first, it.value.second) } }

    // ==== 启动恢复（B06）====

    /**
     * 进程被杀后的状态对账：启动时（Application.onCreate）没有任何解析/下载在跑，
     * 残留的 PARSING/DOWNLOADING 一定是中断态：
     * - PARSING → FAILED（提示重试）
     * - DOWNLOADING → PAUSED（保留 part，用户可「继续」）
     * - 清理无主的 HLS 临时目录
     */
    fun recoverInterrupted() {
        scope.launch {
            runCatching {
                dao.getByStatus(RecordStatus.PARSING).forEach { rec ->
                    dao.updateStatusIn(
                        rec.id, RecordStatus.FAILED, "解析被中断，请重试",
                        listOf(RecordStatus.PARSING),
                    )
                }
                dao.getByStatus(RecordStatus.DOWNLOADING).forEach { rec ->
                    dao.updateStatusIn(
                        rec.id, RecordStatus.PAUSED, null,
                        listOf(RecordStatus.DOWNLOADING),
                    )
                }
                File(context.cacheDir, "xdo").listFiles()?.forEach { f ->
                    if (f.isDirectory && f.name.startsWith("hls_")) f.deleteRecursively()
                }
            }
        }
    }

    // ==== 操作入口 ====

    /** 开始下载（幂等：已有任务则忽略）。force=true 时忽略「仅 WiFi」限制 */
    fun startDownload(recordId: Long, force: Boolean = false) {
        synchronized(jobs) {
            if (jobs.containsKey(recordId)) return
            jobs[recordId] = scope.launch {
                semaphore.withPermit {
                    val rec = dao.getById(recordId) ?: return@withPermit
                    runDownload(rec, force)
                }
                synchronized(jobs) { jobs.remove(recordId) }
                DownloadService.maybeStop(context)
            }
        }
        scope.launch {
            dao.getById(recordId)?.let { DownloadService.start(context, it.id, it.titleForNotif()) }
        }
    }

    fun cancelDownload(recordId: Long) {
        synchronized(jobs) { jobs.remove(recordId)?.cancel() }
        scope.launch {
            val cur = dao.getById(recordId)
            if (cur != null && cur.fileUri != null && cur.completedAt != null) {
                // B19：这是对「已下载完成」记录的重下载被取消——恢复为已完成，保留旧文件不丢
                dao.upsert(cur.copy(status = RecordStatus.DONE, progress = 100, errorMsg = null))
            } else {
                partFiles(recordId).forEach { it.delete() }
                dao.deleteById(recordId)
            }
            clearProgress(recordId)
            DownloadService.maybeStop(context)
        }
    }

    fun pauseDownload(recordId: Long) {
        synchronized(jobs) { jobs.remove(recordId)?.cancel() }
        scope.launch {
            val cur = dao.getById(recordId) ?: return@launch
            if (cur.status == RecordStatus.DOWNLOADING) {
                dao.upsert(cur.copy(status = RecordStatus.PAUSED))
            }
            clearProgress(recordId)
            DownloadService.maybeStop(context)
        }
    }

    /** 删除记录并清理已保存媒体与临时文件 */
    fun deleteRecord(recordId: Long) {
        synchronized(jobs) { jobs.remove(recordId)?.cancel() }
        scope.launch {
            val rec = dao.getById(recordId) ?: return@launch
            if (!rec.fileUri.isNullOrBlank()) {
                deleteMedia(rec.fileUri!!)
            }
            partFiles(recordId).forEach { it.delete() }
            dao.deleteById(recordId)
            clearProgress(recordId)
            DownloadService.maybeStop(context)
        }
    }

    fun clearHistory() {
        synchronized(jobs) { jobs.values.forEach { it.cancel() }; jobs.clear() }
        scope.launch {
            dao.deleteAll()
            // B11：清空全部临时文件（part + HLS 中间目录），并停掉前台服务/通知
            runCatching { File(context.cacheDir, "xdo").deleteRecursively() }
            synchronized(progressMap) { progressMap.clear() }
            DownloadService.maybeStop(context)
        }
    }

    // ==== 通知进度（B08/B28）====

    private fun notifyProgress(recordId: Long, title: String, pct: Int) {
        // 任务已被取消/暂停移出 jobs 时不再上报，避免死条目
        if (synchronized(jobs) { !jobs.containsKey(recordId) }) return
        synchronized(progressMap) { progressMap[recordId] = title to pct }
        DownloadService.refresh(context)
    }

    private fun clearProgress(recordId: Long) {
        val removed = synchronized(progressMap) { progressMap.remove(recordId) } != null
        if (removed) DownloadService.refresh(context)
    }

    // ==== 核心下载 ====

    private suspend fun runDownload(rec: DownloadRecord, force: Boolean) {
        val url = rec.chosenUrl
        if (url.isNullOrBlank()) {
            setFailed(rec, "未获得下载地址，请重新解析")
            return
        }
        val title = rec.titleForNotif()

        // 仅 WiFi 开关：移动网络需手动确认（force=true 放行）
        if (!force && AppPrefsHolder.get(context).wifiOnly && AppUtil.isMetered(context)) {
            setFailed(rec, "当前为移动网络且开启了「仅 WiFi 下载」。请在设置中关闭该限制，或到记录卡片点「继续」确认使用流量")
            return
        }

        dao.upsert(rec.copy(status = RecordStatus.DOWNLOADING, progress = 0, errorMsg = null))
        notifyProgress(rec.id, title, 0)

        val isHls = XResolver.isHlsUrl(url) // B23：剥掉查询参数再判定，带 ?tag= 的 m3u8 不再被当 MP4

        try {
            if (isHls) {
                // HLS 分片流：分片下载后合成单个带声 MP4
                // B01：从 variantsJson 里找回该清晰度对应的音频播放列表 URL
                val audioUrl = VariantsJson.decode(rec.variantsJson)
                    .firstOrNull { it.label == rec.chosenLabel }?.audioUrl
                val hlsTmp = File(context.cacheDir, "xdo/hls_${rec.id}")
                hlsTmp.mkdirs()
                // B14：分片级进度同时写 DB（节流）+ 通知，列表卡片不再长期停在 0%
                var lastDbWrite = 0L
                val hlsFile = withContext(Dispatchers.IO) {
                    HlsDownloader.download(client, hlsTmp, url, audioUrl) { pct ->
                        notifyProgress(rec.id, title, pct)
                        val now = System.currentTimeMillis()
                        if (now - lastDbWrite > 400 &&
                            synchronized(jobs) { jobs.containsKey(rec.id) }
                        ) {
                            lastDbWrite = now
                            // 回调运行在 IO 线程（withContext(IO) 内部），同步写库保证
                            // 与最终 DONE 写入的顺序（不会被滞后的进度写覆盖）
                            kotlinx.coroutines.runBlocking {
                                dao.upsert(rec.copy(status = RecordStatus.DOWNLOADING, progress = pct))
                            }
                        }
                    }
                }
                if (!hlsFile.exists() || hlsFile.length() == 0L) {
                    throw java.io.IOException("HLS 合成结果为空")
                }
                val finished = dao.getById(rec.id) ?: return
                if (finished.status != RecordStatus.DOWNLOADING) return
                val savedUri = MediaSaver.save(context, hlsFile, finished.fileName())
                if (savedUri == null) {
                    // B20：保存失败保留中间文件，用户可直接重试（转存阶段），不必整段重下
                    setFailed(finished, "保存到相册失败，已保留下载内容，可重试")
                    return
                }
                val oldUri = finished.fileUri
                dao.upsert(finished.copy(
                    status = RecordStatus.DONE,
                    progress = 100,
                    fileUri = savedUri,
                    fileSize = hlsFile.length(), // B15：HLS 完成也要写实际大小，列表不再显示 0B
                    errorMsg = null,
                    completedAt = System.currentTimeMillis(),
                ))
                hlsFile.delete()
                hlsTmp.deleteRecursively()
                deleteReplacedMedia(oldUri, savedUri) // B19：重下载成功后清理旧文件，不留重复
                clearProgress(rec.id)
                DownloadService.finishDone(context, rec.id, title)
            } else {
                withContext(Dispatchers.IO) {
                    // B04：part 与 URL 绑定；换清晰度（URL 变化）后旧 part 自动作废
                    val part = partFile(rec.id, url)
                    cleanStaleParts(rec.id, part)
                    val downloaded = part.length()
                    downloadToPart(part, url, downloaded, rec, title)
                }
                // 转存媒体库
                val finished = dao.getById(rec.id) ?: return
                if (finished.status != RecordStatus.DOWNLOADING) return
                val part = partFile(rec.id, url)
                val savedUri = MediaSaver.save(context, part, finished.fileName())
                if (savedUri == null) {
                    // B20：part 保留（不提前删除），可直接重试转存
                    setFailed(finished, "保存到相册失败，已保留下载进度，可重试")
                    return
                }
                val size = part.length() // B25：用实际文件大小，不再用最后一次进度值
                val oldUri = finished.fileUri
                part.delete()
                dao.upsert(finished.copy(
                    status = RecordStatus.DONE,
                    progress = 100,
                    fileUri = savedUri,
                    fileSize = size,
                    errorMsg = null,
                    completedAt = System.currentTimeMillis(),
                ))
                deleteReplacedMedia(oldUri, savedUri)
                clearProgress(rec.id)
                DownloadService.finishDone(context, rec.id, title)
            }
        } catch (e: CancellationException) {
            // B18：取消/暂停时清掉 HLS 临时目录（MP4 的 part 保留供续传）
            runCatching { File(context.cacheDir, "xdo/hls_${rec.id}").deleteRecursively() }
            clearProgress(rec.id)
            throw e
        } catch (e: Exception) {
            // 清理可能残留的 HLS 临时目录
            runCatching { File(context.cacheDir, "xdo/hls_${rec.id}").deleteRecursively() }
            val cur = dao.getById(rec.id) ?: return
            if (cur.status == RecordStatus.PAUSED) return
            setFailed(cur, friendlyError(e))
            return
        }
    }

    /** B19：重下载成功后删除旧媒体文件（URI 不同才删），避免重复文件堆积 */
    private fun deleteReplacedMedia(oldUri: String?, newUri: String) {
        if (oldUri.isNullOrBlank() || oldUri == newUri) return
        deleteMedia(oldUri)
    }

    private fun deleteMedia(uriStr: String) {
        runCatching {
            val u = Uri.parse(uriStr)
            when (u.scheme) {
                "content" -> context.contentResolver.delete(u, null, null)
                "file" -> File(u.path!!).delete()
                else -> Unit
            }
        }
    }

    private suspend fun downloadToPart(
        part: File,
        url: String,
        startAt: Long,
        rec: DownloadRecord,
        title: String,
    ) {
        val resp = request(url, startAt)
        if (resp.code == 416) {
            // 服务端不支持续传：清空重来
            resp.close()
            part.delete()
            val resp2 = request(url, 0)
            consume(resp2, part, 0, rec, title)
        } else {
            consume(resp, part, startAt, rec, title)
        }
    }

    private fun request(url: String, startAt: Long): Response {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .apply { if (startAt > 0) header("Range", "bytes=$startAt-") }
            .build()
        return client.newCall(req).execute()
    }

    private suspend fun consume(
        resp: Response,
        part: File,
        startAt: Long,
        rec: DownloadRecord,
        title: String,
    ) {
        // 服务端忽略 Range 返回 200 全量：丢弃旧 part，从头重下
        var start = startAt
        if (resp.code == 200 && start > 0) {
            part.delete()
            start = 0
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw HttpException(code)
        }
        val total = totalBytesOf(resp, start)
        val body = resp.body ?: run { resp.close(); throw IllegalStateException("无响应体") }

        part.parentFile?.mkdirs()
        var written = start
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(start)
            val buf = ByteArray(CHUNK)
            var lastNotify = 0L
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buf, 0, buf.size)
                    if (n == -1) break
                    raf.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 150) {
                        lastNotify = now
                        val pct = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 99) else 0
                        dao.upsert(rec.copy(
                            status = RecordStatus.DOWNLOADING,
                            progress = pct,
                            fileSize = written.takeIf { total > 0 },
                        ))
                        notifyProgress(rec.id, title, pct)
                    }
                }
            }
        }
        // 完整性校验：连接中途断开时 read 会提前返回 -1，必须核对字节数，
        // 否则会把截断的坏文件当成「已完成」保存（表现为只播前面一段）。
        if (total > 0 && written < total) {
            throw java.io.IOException("下载不完整：已得 ${written} / ${total} 字节，请重试")
        }
        resp.close()
    }

    private fun totalBytesOf(resp: Response, startAt: Long): Long {
        val contentRange = resp.header("Content-Range")
        if (contentRange != null) {
            contentRange.substringAfter('/').toLongOrNull()?.let { return it }
        }
        return resp.body?.contentLength() ?: -1
    }

    private suspend fun setFailed(rec: DownloadRecord, msg: String) {
        dao.upsert(rec.copy(status = RecordStatus.FAILED, progress = 0, errorMsg = msg))
        clearProgress(rec.id)
        DownloadService.finishFailed(context, rec.id, rec.titleForNotif())
    }

    private fun friendlyError(e: Exception): String = when {
        e is HttpException -> "下载失败（HTTP ${e.code}）"
        e is java.net.SocketTimeoutException -> "网络超时，请重试"
        // B24：本地错误（下载不完整/磁盘满）不再笼统报「网络中断」
        e is java.io.IOException && e.message != null && isLocalError(e.message!!) -> e.message!!
        e is java.io.IOException -> "网络中断，请重试"
        else -> e.message ?: "未知错误"
    }

    private fun isLocalError(msg: String): Boolean =
        msg.startsWith("下载不完整") ||
            msg.startsWith("HLS ") ||
            msg.contains("空间不足") ||
            msg.contains("No space left", ignoreCase = true) ||
            msg.contains("ENOSPC", ignoreCase = true)

    // ==== part 文件管理（B04）====

    /** part 文件名绑定 URL 哈希：换清晰度（URL 变化）自动落到新文件，绝不续传混合字节 */
    fun partFile(recordId: Long, url: String): File =
        File(context.cacheDir, "xdo/${recordId}_${urlHash(url)}.part")

    private fun urlHash(url: String): String = try {
        val md = MessageDigest.getInstance("MD5")
        md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
    } catch (e: Exception) {
        Integer.toHexString(url.hashCode())
    }

    /** 该记录的所有 part 文件（含旧版 {id}.part 命名），用于取消/删除时清理 */
    fun partFiles(recordId: Long): List<File> =
        File(context.cacheDir, "xdo")
            .listFiles { f -> f.isFile && (f.name.startsWith("${recordId}_") || f.name.startsWith("${recordId}.")) }
            ?.toList() ?: emptyList()

    /** 下载开始前清掉同记录下不属于当前 URL 的 part（换清晰度场景） */
    private fun cleanStaleParts(recordId: Long, current: File) {
        partFiles(recordId).forEach { if (it.absolutePath != current.absolutePath) it.delete() }
    }

    private class HttpException(val code: Int) : Exception("HTTP $code")

    private companion object {
        const val MAX_CONCURRENT = 2
        const val CHUNK = 64 * 1024
    }
}

internal fun DownloadRecord.titleForNotif(): String =
    text.take(18).ifBlank { "X 视频" }

/** 相册文件名：取帖子内容前 20 字符（去非法字符），追加清晰度后缀 */
internal fun DownloadRecord.fileName(): String {
    val base = text
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(20)
        .ifBlank { handle.ifBlank { "X视频" } }
    val q = chosenLabel?.replace("·", "-")?.replace(" ", "") ?: ""
    return if (q.isBlank()) "$base.mp4" else "${base}_$q.mp4"
}

class AppHolder {
    companion object {
        @Volatile
        var downloadManager: DownloadManager? = null
    }
}
