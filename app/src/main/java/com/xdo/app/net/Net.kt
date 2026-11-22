package com.xdo.app.net

import com.xdo.app.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * N-01：全局共享的网络基座。
 *
 * 原来 DownloadManager / XResolver（2 个）/ UpdateManager / UpdateChecker 各自 new 一个 OkHttpClient，
 * 再加上 Coil 自己的那个，一共 6 套连接池 + 6 套调度线程池：
 * - 解析时刚和 twimg.com 建好的 TLS 连接，下载时用不上，又要重新握手；
 * - 每个客户端各自持有空闲连接和线程，内存、电量都多花。
 *
 * 现在所有客户端都从 [base] 派生（[builder] = base.newBuilder()）：
 * 共用同一个连接池、调度器、拦截器，只在各自的 builder 上改超时、重定向等参数。
 * newBuilder() 派生出的客户端很轻，不会再建池子和线程。
 */
object Net {

    /**
     * 访问 X / twimg CDN 时用的浏览器 UA。
     * 解析（syndication、fxtwitter）、HLS 分片、MP4 直链都用它，保持一致：
     * 原来 MP4 用 XDo/x.y、HLS 用 Chrome UA，同一个 video.twimg.com 上表现成两个不同客户端。
     */
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /** 访问自家资源（GitHub 上的 version.txt / Release APK）时用的应用 UA */
    val APP_UA: String = "XDo/${BuildConfig.VERSION_NAME} (Android)"

    /**
     * 每主机并发上限：HLS 每任务 4 路分片 × 最多 2 个并发任务 = 8 都打到 video.twimg.com；
     * OkHttp 默认 5，会让第二个任务排队。总并发保持默认 64。
     */
    private const val MAX_REQUESTS_PER_HOST = 8

    /** 空闲连接保留数与时长：够容纳一轮 HLS 并发的连接，下一个任务能直接复用 */
    private const val MAX_IDLE_CONNECTIONS = 8
    private const val KEEP_ALIVE_MINUTES = 5L

    private val dispatcher = Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST }

    private val connectionPool = ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)

    /** 没显式设置 UA 的请求统一补 [APP_UA]（不再出现 okhttp/4.x 这种默认 UA） */
    private val defaultUserAgent = Interceptor { chain ->
        chain.proceed(withDefaultUserAgent(chain.request(), APP_UA))
    }

    /** 共享基座。不要直接用它发请求：用 [builder] 派生并设置自己的超时 */
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .addInterceptor(defaultUserAgent)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 派生一个共享连接池/调度器/拦截器的客户端构建器 */
    fun builder(): OkHttpClient.Builder = base.newBuilder()

    /** 请求里没有 User-Agent 时补上 [ua]；已有的保持不变 */
    internal fun withDefaultUserAgent(request: Request, ua: String): Request =
        if (request.header("User-Agent").isNullOrBlank()) {
            request.newBuilder().header("User-Agent", ua).build()
        } else {
            request
        }
}
