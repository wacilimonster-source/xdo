package com.xdo.app

import com.xdo.app.dl.HlsDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P-05：样本缓冲区扩容策略（合成本身依赖 Android MediaMuxer，需真机/仪器测试） */
class HlsMuxBufferTest {

    private val mb = 1024 * 1024
    private val max = 64 * mb

    @Test
    fun `grows by doubling`() {
        assertEquals(4 * mb, HlsDownloader.grownBufSize(2 * mb))
    }

    @Test
    fun `grows enough to fit a known sample size`() {
        val size = HlsDownloader.grownBufSize(1 * mb, needed = 5L * mb)
        assertTrue(size > 5 * mb)
    }

    @Test
    fun `never exceeds the cap`() {
        assertEquals(max, HlsDownloader.grownBufSize(48 * mb))
        assertEquals(max, HlsDownloader.grownBufSize(1 * mb, needed = 500L * mb))
    }
}
