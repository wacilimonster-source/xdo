package com.xdo.app

import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.RecordStatus
import com.xdo.app.dl.titleForNotif
import com.xdo.app.dl.fileName
import org.junit.Assert.assertEquals
import org.junit.Test

/** 文件名与通知标题的纯逻辑覆盖 */
class FileNameTest {

    private fun rec(
        text: String = "这是一个测试视频标题，包含特殊字符：a/b\\c",
        handle: String = "someone",
        chosenLabel: String? = "720p · 720x1280",
    ) = DownloadRecord(
        id = 1,
        sourceUrl = "https://x.com/i/status/1",
        tweetId = "1",
        authorName = "作者",
        handle = handle,
        text = text,
        posterUrl = null,
        durationMs = null,
        variantsJson = "[]",
        chosenLabel = chosenLabel,
        chosenUrl = null,
        status = RecordStatus.DONE,
        progress = 100,
        fileUri = null,
        fileSize = 12345L,
        errorMsg = null,
        positionMs = 0,
        createdAt = 0,
        completedAt = 0,
    )

    @Test
    fun `fileName strips illegal chars and appends quality`() {
        val name = rec().fileName()
        org.junit.Assert.assertFalse(name.contains("/"))
        org.junit.Assert.assertFalse(name.contains("\\"))
        org.junit.Assert.assertTrue(name.endsWith("_720p-720x1280.mp4"))
        org.junit.Assert.assertTrue(name.startsWith("这是一个测试视频标题"))
    }

    @Test
    fun `fileName falls back to handle when text blank`() {
        val name = rec(text = "", chosenLabel = null).fileName()
        assertEquals("someone.mp4", name)
    }

    @Test
    fun `fileName without quality label`() {
        val name = rec(text = "标题", chosenLabel = null).fileName()
        assertEquals("标题.mp4", name)
    }

    @Test
    fun `titleForNotif truncates long text`() {
        assertEquals(18, rec(text = "x".repeat(50)).titleForNotif().length)
        assertEquals("X 视频", rec(text = "").titleForNotif())
    }
}
