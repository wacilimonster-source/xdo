package com.xdo.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.xdo.app.data.AppDatabase
import com.xdo.app.data.AppPrefs
import com.xdo.app.dl.AppHolder
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.dl.DownloadManager
import com.xdo.app.net.Net
import com.xdo.app.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow

/** 跨组件事件总线：分享进来的链接 */
object AppEvents {
    val shares = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 兜底：进程冷启动时 Compose 订阅晚于 handleIntent，先暂存一次 */
    @Volatile
    var lastShare: String? = null

    fun emitShare(text: String) {
        lastShare = text
        shares.tryEmit(text)
    }

    fun takeLastShare(): String? {
        val v = lastShare
        lastShare = null
        return v
    }
}

class XDoApp : Application(), ImageLoaderFactory {

    lateinit var db: AppDatabase
        private set
    lateinit var prefs: AppPrefs
        private set
    lateinit var downloadManager: DownloadManager
        private set

    /** 应用级协程作用域（B31：播放页退出时写断点等后台任务用，不阻塞主线程） */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)
        prefs = AppPrefs(this)
        AppPrefsHolder.init(prefs)
        UpdateManager.init(this)
        downloadManager = DownloadManager(this, db.recordDao())
        AppHolder.downloadManager = downloadManager
        // B06：启动对账——把进程被杀遗留的 PARSING/DOWNLOADING 恢复为可操作状态
        downloadManager.recoverInterrupted()
    }

    /**
     * N-01：Coil（首页/解析页封面图）默认会自建一个 OkHttpClient；
     * 这里改为从共享基座派生，封面图和解析、下载共用连接池与调度线程。
     * 磁盘/内存缓存仍由 Coil 自己管理（不依赖 OkHttp Cache），行为不变。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Net.builder().build() }
            .build()
}
