package com.xdo.app

import com.xdo.app.dl.HlsDownloader
import com.xdo.app.net.XResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HLS 播放列表解析与 B01 音频轨挂载的覆盖 */
class HlsPlaylistTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud1",NAME="audio-0",URI="https://video.twimg.com/audio/0/audio-0.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud2",NAME="audio-128000",URI="https://video.twimg.com/audio/128/audio-128000.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720
        https://video.twimg.com/720/playlist.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360
        https://video.twimg.com/360/playlist.m3u8
    """.trimIndent()

    @Test
    fun `master playlist yields per-resolution options with audio url`() {
        val opts = XResolver.parseMasterPlaylist(master, "https://video.twimg.com/master.m3u8")
        assertEquals(2, opts.size)
        assertTrue(opts.all { it.isHls })
        // B01：每个清晰度都挂上最高码率音频播放列表
        assertTrue(opts.all { it.audioUrl == "https://video.twimg.com/audio/128/audio-128000.m3u8" })
        assertEquals("720p · 1280x720 · HLS", opts[0].label)
        assertEquals("360p · 640x360 · HLS", opts[1].label)
    }

    @Test
    fun `media playlist (no stream-inf) falls back to single option`() {
        val media = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg0.m4s
            #EXTINF:4.0,
            seg1.m4s
        """.trimIndent()
        val base = "https://video.twimg.com/720/playlist.m3u8"
        val opts = XResolver.parseMasterPlaylist(media, base)
        assertEquals(1, opts.size)
        assertEquals(base, opts[0].url)
        assertTrue(opts[0].isHls)
    }

    @Test
    fun `pickPlaylists selects highest bandwidth video and audio`() {
        val (video, audio) = HlsDownloader.pickPlaylists(master, "https://video.twimg.com/master.m3u8")
        assertEquals("https://video.twimg.com/720/playlist.m3u8", video)
        assertEquals("https://video.twimg.com/audio/128/audio-128000.m3u8", audio)
    }

    @Test
    fun `pickPlaylists on media playlist returns base without audio`() {
        val base = "https://video.twimg.com/720/playlist.m3u8"
        val (video, audio) = HlsDownloader.pickPlaylists("#EXTM3U\n#EXTINF:4.0,\nseg0.m4s", base)
        assertEquals(base, video)
        assertNull(audio)
    }

    @Test
    fun `parseMediaPlaylist extracts init and segments with relative resolution`() {
        val media = """
            #EXTM3U
            #EXT-X-MAP:URI="init_video.mp4"
            #EXTINF:4.000,
            seg-0.m4s
            #EXTINF:4.000,
            seg-1.m4s
        """.trimIndent()
        val base = "https://video.twimg.com/720/playlist.m3u8"
        val (init, segs) = HlsDownloader.parseMediaPlaylist(media, base)
        assertEquals("https://video.twimg.com/720/init_video.mp4", init)
        assertEquals(
            listOf(
                "https://video.twimg.com/720/seg-0.m4s",
                "https://video.twimg.com/720/seg-1.m4s",
            ),
            segs,
        )
    }

    @Test
    fun `hls url detection ignores query params`() {
        // B23：带查询参数的 m3u8 不再被当 MP4
        assertTrue(XResolver.isHlsUrl("https://x.com/playlist.m3u8?tag=12"))
        assertTrue(XResolver.isHlsUrl("https://x.com/PLAYLIST.M3U8"))
        org.junit.Assert.assertFalse(XResolver.isHlsUrl("https://x.com/video.mp4"))
    }

    @Test
    fun `resolveUri handles absolute root-relative and relative`() {
        val base = "https://video.twimg.com/720/playlist.m3u8"
        assertEquals("https://video.twimg.com/720/seg.m4s", XResolver.resolveUri(base, "seg.m4s"))
        assertEquals("https://video.twimg.com/init.mp4", XResolver.resolveUri(base, "/init.mp4"))
        assertEquals("https://cdn.x.com/a.mp4", XResolver.resolveUri(base, "https://cdn.x.com/a.mp4"))
    }
}
