package com.xdo.app.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xdo.app.XDoApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(recordId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as XDoApp
    val dao = app.db.recordDao()

    // 读取记录（Flow 收集，简单用一次加载）
    var fileUri by remember { mutableStateOf<String?>(null) }
    var recordTitle by remember { mutableStateOf("") }
    var tweetId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(recordId) {
        val rec = app.db.recordDao().getById(recordId)
        fileUri = rec?.fileUri
        recordTitle = rec?.text?.take(30).orEmpty()
        tweetId = rec?.tweetId
    }

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // 加载+恢复断点
    LaunchedEffect(fileUri) {
        val uri = fileUri
        if (!uri.isNullOrBlank()) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            player.prepare()
            val saved = app.db.recordDao().getById(recordId)?.positionMs ?: 0
            if (saved > 0) {
                player.seekTo(saved)
            }
            player.playWhenReady = false
        }
    }

    // 退出时保存断点
    DisposableEffect(Unit) {
        onDispose {
            player.currentPosition.takeIf { it > 0 }?.let { pos ->
                kotlinx.coroutines.runBlocking {
                    val cur = app.db.recordDao().getById(recordId) ?: return@runBlocking
                    app.db.recordDao().upsert(cur.copy(positionMs = pos))
                }
            }
            player.release()
        }
    }

    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recordTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
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
                        Icon(Icons.Filled.Share, "分享")
                    }
                    IconButton(onClick = {
                        val id = tweetId
                        if (id != null) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("tweet", "https://x.com/i/status/$id"))
                        }
                    }) {
                        Icon(Icons.Filled.ContentCopy, "复制链接")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, "删除")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = true
                            this.player = player
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "位置已记忆，下次自动续播",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                }) {
                    Icon(if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (player.isPlaying) "暂停" else "播放")
                }
                TextButton(onClick = {
                    player.seekTo(0)
                    player.playWhenReady = true
                }) {
                    Text("从头播放")
                }
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