package com.xdo.app

import android.app.Application
import com.xdo.app.data.AppDatabase
import com.xdo.app.data.AppPrefs
import com.xdo.app.dl.AppHolder
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.dl.DownloadManager
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

class XDoApp : Application() {

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
}
