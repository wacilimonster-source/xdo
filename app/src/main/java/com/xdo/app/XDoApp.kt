package com.xdo.app

import android.app.Application
import com.xdo.app.data.AppDatabase
import com.xdo.app.data.AppPrefs
import com.xdo.app.dl.AppHolder
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.dl.DownloadManager
import com.xdo.app.update.UpdateManager
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

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)
        prefs = AppPrefs(this)
        AppPrefsHolder.init(prefs)
        UpdateManager.init(this)
        downloadManager = DownloadManager(this, db.recordDao())
        AppHolder.downloadManager = downloadManager
    }
}