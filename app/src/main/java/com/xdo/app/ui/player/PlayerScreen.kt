package com.xdo.app.ui.player

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xdo.app.XDoApp

/** 沉浸式全屏播放：状态栏/导航栏隐藏，视频占满整个屏幕，控制器用播放器自带的 */
@Composable
fun PlayerScreen(recordId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as XDoApp
    val dao = app.db.recordDao()

    var fileUri by remember { mutableStateOf<String?>(null) }
    var recordTitle by remember { mutableStateOf("") }
    var tweetId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recordId) {
        val rec = dao.getById(recordId)
        fileUri = rec?.fileUri
        recordTitle = rec?.text?.take(30).orEmpty()
        tweetId = rec?.tweetId
    }

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // 加载 + 恢复断点
    LaunchedEffect(fileUri) {
        val uri = fileUri
        if (!uri.isNullOrBlank()) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            player.prepare()
            val saved = dao.getById(recordId)?.positionMs ?: 0
            if (saved > 0) player.seekTo(saved)
            player.playWhenReady = false
        }
    }

    // 沉浸式全屏：隐藏系统栏，退出时恢复
    val activity = context as? Activity
    DisposableEffect(Unit) {
        val controller = activity?.let { act ->
            val c = WindowCompat.getInsetsController(act.window, act.window.decorView)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            player.currentPosition.takeIf { it > 0 }?.let { pos ->
                kotlinx.coroutines.runBlocking {
                    val cur = dao.getById(recordId) ?: return@runBlocking
                    dao.upsert(cur.copy(positionMs = pos))
                }
            }
            player.release()
        }
    }

    var confirmDelete by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 视频占满全屏，自动播放/暂停与进度控制由 PlayerView 自带控制器完成
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部悬浮操作条（半透明，不挡视频）
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x88000000))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = {
                val uri = fileUri
                if (uri != null) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                        clipData = ClipData.newRawUri("video", Uri.parse(uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "分享视频"))
                }
            }) {
                Icon(Icons.Filled.Share, "分享", tint = Color.White)
            }
            IconButton(onClick = {
                val id = tweetId
                if (id != null) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("tweet", "https://x.com/i/status/$id"))
                }
            }) {
                Icon(Icons.Filled.ContentCopy, "复制链接", tint = Color.White)
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, "删除", tint = Color.White)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除下载？") },
            text = { Text("将从相册与历史记录中删除此视频文件。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    app.downloadManager.deleteRecord(recordId)
                    onBack()
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}