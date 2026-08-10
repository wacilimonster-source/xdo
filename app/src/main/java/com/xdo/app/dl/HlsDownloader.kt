package com.xdo.app.dl

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * HLS(m3u8) 下载器。
 *
 * X 的长视频（如 7 分钟）只提供 HLS 分片流：视频是 CMAF/fMP4（init 段 + 多个 .m4s 分片），
 * 且音视频分离（音频在独立的播放列表里）。直接把 m3u8 当单文件下载会得到「只播前面一段」的损坏文件。
 * 这里分片下载所有视频/音频分片，再用 MediaExtractor + MediaMuxer 合成一个带声 MP4。
 */
object HlsDownloader {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val TAG = "HlsDownloader"

    /**
     * 下载并合成 HLS 为单个 MP4 文件。
     * @param client    复用下载器的 OkHttp（带超时）
     * @param tmpDir   缓存目录，用于存放中间文件
     * @param masterUrl 主播放列表或媒体播放列表 URL
     * @param onProgress 0..100 进度回调
     * @return 合成后的 MP4 文件；失败抛异常
     */
    suspend fun download(
        client: OkHttpClient,
        tmpDir: File,
        masterUrl: String,
        onProgress: (Int) -> Unit,
    ): File {
        tmpDir.mkdirs()
        val videoFile = File(tmpDir, "hls_video.mp4")
        val audioFile = File(tmpDir, "hls_audio.mp4")
        val outFile = File(tmpDir, "hls_muxed.mp4")
        listOf(videoFile, audioFile, outFile).forEach { it.delete() }

        // 1) 取主播放列表，选出最高清视频变体 + 音频变体
        val master = fetchText(client, masterUrl)
        val (videoPlaylist, audioPlaylist) = pickPlaylists(master, masterUrl)

        // 2) 解析视频媒体播放列表（init + 分片）
        val vMedia = fetchText(client, videoPlaylist)
        if (vMedia.contains("#EXT-X-KEY:METHOD=") &&
            !vMedia.contains("#EXT-X-KEY:METHOD=NONE")
        ) {
            throw UnsupportedOperationException("该视频已加密，暂不支持下载")
        }
        val (vInit, vSegs) = parseMediaPlaylist(vMedia, videoPlaylist)
        Log.i(TAG, "视频分片数: ${vSegs.size}")

        // 3) 解析音频媒体播放列表（若有）
        var aInit: String? = null
        var aSegs: List<String> = emptyList()
        if (audioPlaylist != null) {
            val aMedia = fetchText(client, audioPlaylist)
            val (ai, as_) = parseMediaPlaylist(aMedia, audioPlaylist)
            aInit = ai
            aSegs = as_
            Log.i(TAG, "音频分片数: ${aSegs.size}")
        }

        val total = vSegs.size + aSegs.size + 1 // +1 为合成步骤
        var done = 0
        fun tick() {
            onProgress((done * 100 / total).coerceIn(0, 99))
        }

        // 4) 下载视频分片
        downloadConcat(client, vInit, vSegs, videoFile)
        done += vSegs.size; tick()

        // 5) 下载音频分片
        if (aSegs.isNotEmpty()) {
            downloadConcat(client, aInit, aSegs, audioFile)
        }
        done += aSegs.size; tick()

        // 6) 合成
        val ok = mux(videoFile, if (aSegs.isNotEmpty()) audioFile else null, outFile)
        done++; onProgress(if (ok) 100 else 99)
        if (!ok) {
            // 合成失败兜底：至少保留无声视频
            if (outFile.exists()) outFile.delete()
            videoFile.copyTo(outFile, overwrite = true)
        }
        return outFile
    }

    // ===== 播放列表解析 =====

    /** 从主播放列表选出视频播放列表与音频播放列表 URL */
    private fun pickPlaylists(master: String, base: String): Pair<String, String?> {
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
                if (line.contains("TYPE=AUDIO")) {
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
    private fun parseMediaPlaylist(body: String, base: String): Pair<String?, List<String>> {
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

    private fun resolveUri(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        if (uri.startsWith("/")) {
            val m = Regex("""(https?://[^/]+)""").find(base)
            return (m?.groupValues?.get(1) ?: "") + uri
        }
        val slash = base.lastIndexOf('/')
        return if (slash >= 0) base.substring(0, slash + 1) + uri else uri
    }

    // ===== 下载 =====

    private fun fetchText(client: OkHttpClient, url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("播放列表获取失败 HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("播放列表为空")
        }
    }

    /** 下载 init 段（若有）后依次追加所有分片到同一文件 */
    private fun downloadConcat(
        client: OkHttpClient,
        init: String?,
        segs: List<String>,
        out: File,
    ) {
        FileOutputStream(out).use { fos ->
            init?.let { fos.write(fetchBytes(client, it)) }
            for (s in segs) {
                fos.write(fetchBytes(client, s))
            }
        }
    }

    private fun fetchBytes(client: OkHttpClient, url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("分片下载失败 HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw IllegalStateException("分片为空")
        }
    }

    // ===== 合成（MediaExtractor + MediaMuxer） =====

    /**
     * 把视频 fMP4 与音频 fMP4 合成单个 MP4。
     * @return true 成功；false 失败
     */
    private fun mux(videoFile: File, audioFile: File?, outFile: File): Boolean {
        if (!videoFile.exists()) return false
        val muxer = try {
            MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e(TAG, "MediaMuxer 创建失败: ${e.message}")
            return false
        }
        val ve = MediaExtractor()
        try {
            ve.setDataSource(videoFile.path)
        } catch (e: Exception) {
            Log.e(TAG, "视频解析失败: ${e.message}")
            muxer.release(); return false
        }
        val vTrack = findTrack(ve, "video/")
        if (vTrack < 0) {
            ve.release(); muxer.release()
            return false
        }
        ve.selectTrack(vTrack)

        var ae: MediaExtractor? = null
        var aTrack = -1
        if (audioFile != null && audioFile.exists()) {
            ae = MediaExtractor()
            try {
                ae.setDataSource(audioFile.path)
                aTrack = findTrack(ae, "audio/")
                if (aTrack >= 0) ae.selectTrack(aTrack)
            } catch (e: Exception) {
                Log.w(TAG, "音频解析失败，仅保留视频: ${e.message}")
                ae.release(); ae = null; aTrack = -1
            }
        }

        val vIdx = muxer.addTrack(ve.getTrackFormat(vTrack))
        val aIdx = if (ae != null && aTrack >= 0) muxer.addTrack(ae.getTrackFormat(aTrack)) else -1
        muxer.start()
        try {
            writeTrack(ve, vIdx, muxer)
            if (ae != null && aTrack >= 0) writeTrack(ae, aIdx, muxer)
        } catch (e: Exception) {
            Log.e(TAG, "写入样本失败: ${e.message}")
            runCatching { muxer.stop() }
            muxer.release(); ve.release(); ae?.release()
            return false
        }
        muxer.stop()
        muxer.release()
        ve.release()
        ae?.release()
        return true
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun writeTrack(extractor: MediaExtractor, idx: Int, muxer: MediaMuxer) {
        var buf = ByteBuffer.allocate(1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            if (size > buf.capacity()) {
                buf = ByteBuffer.allocate(size + 1024)
            }
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.offset = 0
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(idx, buf, info)
            extractor.advance()
        }
    }
}
