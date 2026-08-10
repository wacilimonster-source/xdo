package com.xdo.app.dl

import android.content.Context
import android.net.Uri
import com.xdo.app.BuildConfig
import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.RecordDao
import com.xdo.app.data.RecordStatus
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
import java.util.concurrent.TimeUnit

/**
 * 下载管理器（Application 单例持有）。
 * - 并发上限 2；断点续传：写 cache/xdo/{id}.part，带 Range 头续传
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
            partFile(recordId).delete()
            dao.deleteById(recordId)
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
            DownloadService.maybeStop(context)
        }
    }

    /** 删除记录并清理已保存媒体与临时文件 */
    fun deleteRecord(recordId: Long) {
        synchronized(jobs) { jobs.remove(recordId)?.cancel() }
        scope.launch {
            val rec = dao.getById(recordId) ?: return@launch
            if (!rec.fileUri.isNullOrBlank()) {
                runCatching { context.contentResolver.delete(Uri.parse(rec.fileUri), null, null) }
            }
            partFile(recordId).delete()
            dao.deleteById(recordId)
            DownloadService.maybeStop(context)
        }
    }

    fun clearHistory() {
        synchronized(jobs) { jobs.values.forEach { it.cancel() }; jobs.clear() }
        scope.launch { dao.deleteAll() }
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
            setFailed(rec, "移动网络已限制，请在设置关闭「仅 WiFi 下载」或点重试强制下载")
            return
        }

        dao.upsert(rec.copy(status = RecordStatus.DOWNLOADING, progress = 0, errorMsg = null))
        DownloadService.updateProgress(context, title, 0)

        val part = partFile(rec.id)
        val isHls = url.endsWith(".m3u8", ignoreCase = true)

        try {
            if (isHls) {
                // HLS 分片流：分片下载后合成单个带声 MP4
                val hlsTmp = File(context.cacheDir, "xdo/hls_${rec.id}")
                hlsTmp.mkdirs()
                val hlsFile = withContext(Dispatchers.IO) {
                    HlsDownloader.download(client, hlsTmp, url) { pct ->
                        DownloadService.updateProgress(context, title, pct)
                    }
                }
                if (!hlsFile.exists() || hlsFile.length() == 0L) {
                    throw java.io.IOException("HLS 合成结果为空")
                }
                val finished = dao.getById(rec.id) ?: return
                if (finished.status != RecordStatus.DOWNLOADING) return
                val savedUri = MediaSaver.save(context, hlsFile, finished.fileName())
                hlsFile.delete()
                hlsTmp.deleteRecursively()
                if (savedUri == null) {
                    setFailed(finished, "保存到相册失败")
                    return
                }
                dao.upsert(finished.copy(
                    status = RecordStatus.DONE,
                    progress = 100,
                    fileUri = savedUri,
                    errorMsg = null,
                    completedAt = System.currentTimeMillis(),
                ))
                DownloadService.finishDone(context, title)
            } else {
                withContext(Dispatchers.IO) {
                    val downloaded = part.length()
                    downloadToPart(part, url, downloaded, rec, title)
                }
                // 转存媒体库
                val finished = dao.getById(rec.id) ?: return
                if (finished.status != RecordStatus.DOWNLOADING) return
                val savedUri = MediaSaver.save(context, part, finished.fileName())
                part.delete()
                if (savedUri == null) {
                    setFailed(finished, "保存到相册失败")
                    return
                }
                dao.upsert(finished.copy(
                    status = RecordStatus.DONE,
                    progress = 100,
                    fileUri = savedUri,
                    errorMsg = null,
                    completedAt = System.currentTimeMillis(),
                ))
                DownloadService.finishDone(context, title)
            }
        } catch (e: CancellationException) {
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
                        DownloadService.updateProgress(context, title, pct)
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
        DownloadService.finishFailed(context, rec.titleForNotif())
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is HttpException -> "下载失败（HTTP ${e.code}）"
        is java.net.SocketTimeoutException -> "网络超时"
        is java.io.IOException -> "网络中断，请重试"
        else -> e.message ?: "未知错误"
    }

    fun partFile(recordId: Long): File = File(context.cacheDir, "xdo/$recordId.part")

    private class HttpException(val code: Int) : Exception("HTTP $code")

    private companion object {
        const val MAX_CONCURRENT = 2
        const val CHUNK = 64 * 1024
    }
}

private fun DownloadRecord.titleForNotif(): String =
    text.take(18).ifBlank { "X 视频" }

private fun DownloadRecord.fileName(): String {
    val q = chosenLabel?.replace("·", "-")?.replace(" ", "") ?: "video"
    return "${handle.ifBlank { "x" }}_${tweetId}_$q.mp4"
}

class AppHolder {
    companion object {
        @Volatile
        var downloadManager: DownloadManager? = null
    }
}