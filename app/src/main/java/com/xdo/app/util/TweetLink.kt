package com.xdo.app.util

import java.util.regex.Pattern

object TweetLink {

    /**
     * B13 修复：
     * - 支持任意层级路径：x.com/i/web/status/1、x.com/user/status/2、x.com/status/3；
     * - 域名前加负向断言，避免 "max.com/x.com..." 之类的子串误匹配（如 max.com/foo/status/789）；
     * - status/ 后允许查询参数。
     */
    private val TWEET_ID = Pattern.compile(
        """(?<![A-Za-z0-9])(?:x|twitter)\.com/(?:[^/\s?#]+/)*status/(\d+)""",
        Pattern.CASE_INSENSITIVE,
    )

    fun extractTweetId(text: String): String? {
        val m = TWEET_ID.matcher(text)
        return if (m.find()) m.group(1) else null
    }

    fun findUrl(text: String): String? {
        val m = Pattern.compile("""https?://t\.co/\w+""").matcher(text)
        return if (m.find()) m.group(0) else null
    }

    /**
     * 只有能真正提取出推文 ID 才认为是推文链接。
     * 修复：之前对 t.co 返回 true 但 extractTweetId 不支持 → 永远解析失败。
     */
    fun looksLikeTweetText(text: String): Boolean = extractTweetId(text) != null
}
