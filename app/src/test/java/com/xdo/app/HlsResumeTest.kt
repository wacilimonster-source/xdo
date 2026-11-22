package com.xdo.app

import com.xdo.app.dl.HlsDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** P-04：HLS 断点续传——分片目录清单核对与已完成分片统计 */
class HlsResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val init = "https://video.twimg.com/v/init.mp4"
    private val segs = (0 until 5).map { "https://video.twimg.com/v/seg$it.m4s" }

    private fun dir(): File = File(tmp.root, "segs_v")

    private fun writeSeg(d: File, i: Int, bytes: Int = 16) =
        HlsDownloader.segFile(d, i).writeBytes(ByteArray(bytes) { 1 })

    @Test
    fun `fresh directory starts from zero and writes manifest`() {
        val d = dir()
        assertEquals(0, HlsDownloader.prepareTrackDir(d, init, segs))
        assertTrue(File(d, "manifest.txt").isFile)
    }

    @Test
    fun `same manifest keeps completed segments and drops tmp leftovers`() {
        val d = dir()
        HlsDownloader.prepareTrackDir(d, init, segs)
        writeSeg(d, 0)
        writeSeg(d, 1)
        writeSeg(d, 3)
        val leftover = File(d, "seg_2.seg.tmp").apply { writeBytes(ByteArray(8)) }

        assertEquals(3, HlsDownloader.prepareTrackDir(d, init, segs))
        assertFalse("中断残留的 .tmp 应被清理", leftover.exists())
        assertTrue(HlsDownloader.segFile(d, 0).exists())
    }

    @Test
    fun `empty segment file is not counted as complete`() {
        val d = dir()
        HlsDownloader.prepareTrackDir(d, init, segs)
        writeSeg(d, 0)
        writeSeg(d, 1, bytes = 0)
        assertEquals(1, HlsDownloader.prepareTrackDir(d, init, segs))
    }

    @Test
    fun `changed playlist invalidates old segments`() {
        val d = dir()
        HlsDownloader.prepareTrackDir(d, init, segs)
        writeSeg(d, 0)
        writeSeg(d, 1)

        // 换清晰度：分片 URL 变了
        val other = segs.map { it.replace("/v/", "/v480/") }
        assertEquals(0, HlsDownloader.prepareTrackDir(d, init, other))
        assertFalse(HlsDownloader.segFile(d, 0).exists())
        assertFalse(HlsDownloader.segFile(d, 1).exists())
    }

    @Test
    fun `changed init segment also invalidates`() {
        val d = dir()
        HlsDownloader.prepareTrackDir(d, init, segs)
        writeSeg(d, 0)
        assertEquals(0, HlsDownloader.prepareTrackDir(d, "https://video.twimg.com/v/init2.mp4", segs))
    }
}
