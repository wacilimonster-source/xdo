package com.xdo.app

import com.xdo.app.net.Net
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** N-01：共享网络基座——默认 UA 补齐与客户端派生 */
class NetTest {

    private fun req(ua: String? = null) = Request.Builder()
        .url("https://raw.githubusercontent.com/x/y/main/version.txt")
        .apply { if (ua != null) header("User-Agent", ua) }
        .build()

    @Test
    fun `missing user agent gets the default`() {
        val out = Net.withDefaultUserAgent(req(), "XDo/1.0 (Android)")
        assertEquals("XDo/1.0 (Android)", out.header("User-Agent"))
    }

    @Test
    fun `explicit user agent is kept`() {
        val r = req(Net.BROWSER_UA)
        val out = Net.withDefaultUserAgent(r, "XDo/1.0 (Android)")
        assertSame("已有 UA 时不应重建请求", r, out)
        assertEquals(Net.BROWSER_UA, out.header("User-Agent"))
    }

    @Test
    fun `derived clients share pool and dispatcher`() {
        val a = Net.builder().build()
        val b = Net.builder().build()
        assertSame(Net.base.connectionPool, a.connectionPool)
        assertSame(a.connectionPool, b.connectionPool)
        assertSame(Net.base.dispatcher, b.dispatcher)
        assertEquals(8, a.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun `app ua carries version`() {
        assertTrue(Net.APP_UA.startsWith("XDo/"))
    }
}
