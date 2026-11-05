package com.xdo.app

import com.xdo.app.util.TweetLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B13：推文链接提取的正则覆盖（含 /i/web/status/、防误匹配） */
class TweetLinkTest {

    @Test
    fun `standard x com link`() {
        assertEquals("123", TweetLink.extractTweetId("https://x.com/user/status/123"))
    }

    @Test
    fun `twitter com link`() {
        assertEquals("456", TweetLink.extractTweetId("https://twitter.com/someone/status/456"))
    }

    @Test
    fun `i web status link`() {
        // 之前被拒绝的格式
        assertEquals("910031516746514432", TweetLink.extractTweetId("https://x.com/i/web/status/910031516746514432"))
        assertEquals("910031516746514432", TweetLink.extractTweetId("https://twitter.com/i/web/status/910031516746514432"))
    }

    @Test
    fun `no handle status link`() {
        assertEquals("111", TweetLink.extractTweetId("https://x.com/status/111"))
    }

    @Test
    fun `link with query params`() {
        assertEquals("333", TweetLink.extractTweetId("https://x.com/u/status/333?s=20&t=abc"))
    }

    @Test
    fun `photo suffix after id`() {
        assertEquals("444", TweetLink.extractTweetId("https://x.com/username/status/444/photo/1"))
    }

    @Test
    fun `max com must not match`() {
        assertNull(TweetLink.extractTweetId("https://max.com/foo/status/789"))
    }

    @Test
    fun `plain text without link`() {
        assertNull(TweetLink.extractTweetId("这是一段普通文本，没有链接"))
    }

    @Test
    fun `t co only text is not a tweet link`() {
        // 修复：之前 looksLikeTweetText 对 t.co 返回 true 但无法提取 ID，永远解析失败
        val text = "看看这个 https://t.co/abc123"
        assertFalse(TweetLink.looksLikeTweetText(text))
    }

    @Test
    fun `looksLikeTweetText agrees with extractTweetId`() {
        assertTrue(TweetLink.looksLikeTweetText("https://x.com/i/web/status/910031516746514432"))
        assertFalse(TweetLink.looksLikeTweetText("https://example.com/status/1"))
    }
}
