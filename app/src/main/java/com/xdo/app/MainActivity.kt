package com.xdo.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.xdo.app.ui.AppRoot
import com.xdo.app.ui.theme.XDoTheme
import com.xdo.app.util.TweetLink

/** 首页通知权限前置说明（H-10）→ 拉起系统权限弹窗的桥（权限 launcher 只能注册在 Activity） */
object PermissionBridge {
    @Volatile
    var requestNotif: (() -> Unit)? = null
}

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /** B05：Android 8/9 保存到公共相册需要运行时申请写权限（Manifest 只声明不够） */
    private val legacyStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PermissionBridge.requestNotif = { notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        setContent {
            XDoTheme {
                AppRoot(onOpenSettings = {})
            }
        }
        requestLegacyStorageIfNeeded()
        // H-10：通知权限不再裸弹系统弹窗，改由首页前置说明确认后再拉起（见 HomeScreen / PermissionBridge）
        // B22：仅冷启动处理一次分享 Intent；旋转/配置变化重建时（savedInstanceState != null）
        // 不再重复处理，setIntent 保留的原 SEND intent 不会二次触发解析
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        // 严格过滤：仅真正的 X 推文链接（含 status/数字）才触发解析，其他分享静默忽略
        if (TweetLink.extractTweetId(text) != null) {
            AppEvents.emitShare(text)
        }
    }

    private fun requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT <= 28) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                legacyStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}
