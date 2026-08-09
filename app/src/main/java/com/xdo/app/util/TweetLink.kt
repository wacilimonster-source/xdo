package com.xdo.app.util

import java.util.regex.Pattern

object TweetLink {

    private val TWEET_ID = Pattern.compile(
        """(?:x|twitter)\.com/[^/\s?]+/status/(\d+)""",
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

    fun looksLikeTweetText(text: String): Boolean =
        text.contains("x.com/") || text.contains("twitter.com/") || findUrl(text) != null
}