package com.xdo.app

import com.xdo.app.dl.HlsDownloader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** P-03：HLS 分片重试判定（哪些错误值得重试，哪些应立即失败） */
class HlsRetryTest {

    private fun http(code: Int) = HlsDownloader.SegmentHttpException(code)

    @Test
    fun `transient http errors are retried`() {
        listOf(408, 429, 500, 502, 503, 504).forEach {
            assertTrue("HTTP $it 应重试", HlsDownloader.isRetryable(http(it)))
        }
    }

    @Test
    fun `deterministic http errors fail fast`() {
        listOf(400, 401, 403, 404, 410).forEach {
            assertFalse("HTTP $it 不应重试", HlsDownloader.isRetryable(http(it)))
        }
    }

    @Test
    fun `network io errors are retried`() {
        assertTrue(HlsDownloader.isRetryable(IOException("unexpected end of stream")))
        assertTrue(HlsDownloader.isRetryable(SocketTimeoutException("timeout")))
        assertTrue(HlsDownloader.isRetryable(IOException("HLS 分片不完整：10 / 20 字节")))
    }

    @Test
    fun `disk full is not retried`() {
        assertFalse(HlsDownloader.isRetryable(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun `non io errors are not retried`() {
        assertFalse(HlsDownloader.isRetryable(IllegalStateException("x")))
        assertFalse(HlsDownloader.isRetryable(UnsupportedOperationException("该视频已加密，暂不支持下载")))
    }
}
