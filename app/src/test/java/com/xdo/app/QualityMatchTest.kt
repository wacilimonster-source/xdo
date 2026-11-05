package com.xdo.app

import com.xdo.app.data.QualityMatch
import com.xdo.app.data.QualityOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R2：旧记录重新解析后，按 URL/label/档位前缀找回清晰度 */
class QualityMatchTest {

    private val hls720 = QualityOption(
        label = "720p · 720x1280 · HLS", width = 720, height = 1280, bitrateKbps = 1150,
        url = "https://v/720.m3u8", isHls = true, audioUrl = "https://v/audio.m3u8",
    )
    private val mp4720 = QualityOption(
        label = "720p · 720x1280", width = 720, height = 1280, bitrateKbps = 0,
        url = "https://v/720.mp4", isHls = false,
    )
    private val hls360 = QualityOption(
        label = "360p · 320x568 · HLS", width = 320, height = 568, bitrateKbps = 289,
        url = "https://v/360.m3u8", isHls = true, audioUrl = "https://v/audio.m3u8",
    )
    private val fresh = listOf(hls720, mp4720, hls360)

    @Test
    fun `exact url wins even if label changed`() {
        val r = QualityMatch.pick(fresh, "旧标签", "https://v/720.mp4")
        assertEquals(mp4720, r)
    }

    @Test
    fun `exact label when url token changed`() {
        val r = QualityMatch.pick(fresh, "720p · 720x1280 · HLS", "https://old/url.m3u8")
        assertEquals(hls720, r)
    }

    @Test
    fun `hls stream label picks first hls`() {
        val r = QualityMatch.pick(fresh, "HLS 流", "https://old/master.m3u8")
        assertEquals(hls720, r)
    }

    @Test
    fun `blank label picks first hls`() {
        assertEquals(hls720, QualityMatch.pick(fresh, null, null))
    }

    @Test
    fun `old hls label falls back to same resolution mp4`() {
        val onlyMp4 = listOf(mp4720)
        val r = QualityMatch.pick(onlyMp4, "720p · 720x1280 · HLS", "https://old/720.m3u8")
        assertEquals(mp4720, r)
    }

    @Test
    fun `no match returns null`() {
        assertNull(QualityMatch.pick(listOf(hls360), "1080p · 1080x1920 · HLS", "https://old.m3u8"))
    }
}
