package com.xdo.app.ui.player

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.xdo.app.XDoApp
import com.xdo.app.ui.components.XSnackbarHost
import com.xdo.app.ui.components.recordTitle
import com.xdo.app.ui.theme.XColors
import kotlinx.coroutines.launch

/**
 * 沉浸式全屏播放（单深色主题）。
 * P-01：顶部常驻四联按钮收敛为「返回 + 标题 + ⋯」，分享/复制/删除收进底部菜单，
 *       顶栏跟随播放器控制器一起显隐，不再常驻遮挡画面。
 * P-02/P-03：复制链接有 Snackbar 反馈；播放错误给出可重试的错误卡片（B31）。
 */
@OptIn(ExperimentalMaterial3Api::class)
// setShowNext/PreviousButton 属于 media3 UnstableApi（lint UnsafeOptInUsageError）
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(recordId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as XDoApp
    val dao = app.db.recordDao()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var fileUri by remember { mutableStateOf<String?>(null) }
    var recordTitleText by remember { mutableStateOf("") }
    var tweetId by remember { mutableStateOf<String?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    var showMenuSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(recordId) {
        val rec = dao.getById(recordId)
        fileUri = rec?.fileUri
        recordTitleText = rec?.let { recordTitle(it) }.orEmpty()
        tweetId = rec?.tweetId
    }

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // B31：播放错误（文件被删/解码失败）不再黑屏，给出可操作的提示
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playError = "文件可能已损坏或被系统清理"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
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

    /** P-04：错误后重试 = 重新装载媒体源 */
    fun retryPlayback() {
        playError = null
        val uri = fileUri
        if (!uri.isNullOrBlank()) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun copyTweetLink() {
        val id = tweetId
        if (id != null) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("tweet", "https://x.com/i/status/$id"))
            scope.launch { snackbarHostState.showSnackbar("链接已复制") }
        } else {
            scope.launch { snackbarHostState.showSnackbar("未获取到推文链接") }
        }
    }

    fun shareVideo() {
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
            // B31：断点写入改走应用级后台作用域（定向 UPDATE，不整行 REPLACE），
            // 不再在主线程 runBlocking 阻塞退出动画
            val pos = player.currentPosition
            if (pos > 0) {
                app.appScope.launch {
                    runCatching { dao.updatePosition(recordId, pos) }
                }
            }
            player.release()
        }
    }

    // 顶栏跟随系统控制器显隐（P-01/P-02：行为一致，不常驻遮挡）
    var topBarVisible by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                val exo = player
                PlayerView(ctx).apply {
                    useController = true
                    this.player = exo
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    // P-01/P-02：系统控制条出现/隐藏时同步顶栏，行为一致
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            topBarVisible = visibility == View.VISIBLE
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // P-01：顶部渐变操作条（返回 + 标题 + ⋯），随控制器一起显隐
        AnimatedVisibility(
            visible = topBarVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC000000), Color.Transparent),
                        )
                    )
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Text(
                    recordTitleText,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                )
                IconButton(onClick = { showMenuSheet = true }) {
                    Icon(Icons.Filled.MoreVert, "更多", tint = Color.White)
                }
            }
        }

        // P-03：Snackbar（复制链接等反馈），悬浮在控制条上方
        XSnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
        )

        // B31/P-04：播放失败 → 中央错误卡片（可重试 / 返回），不再是一行白字
        AnimatedVisibility(
            visible = playError != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF216181C))
                    .padding(horizontal = 24.dp, vertical = 22.dp),
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    null,
                    tint = XColors.Red,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("视频无法播放", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    playError ?: "",
                    color = XColors.Text2,
                    fontSize = 12.5.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { retryPlayback() },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = XColors.Blue),
                    ) { Text("重试", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = onBack) { Text("返回", color = XColors.Text2) }
                }
            }
        }
    }

    // P-01：⋯ 底部菜单（分享 / 复制 / 删除）
    if (showMenuSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMenuSheet = false },
            sheetState = sheetState,
            containerColor = XColors.Surface,
            contentColor = XColors.Text,
        ) {
            Text(
                recordTitleText,
                fontSize = 13.5.sp,
                color = XColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            SheetAction(Icons.Filled.Share, "分享视频") {
                showMenuSheet = false
                shareVideo()
            }
            SheetAction(Icons.Filled.ContentCopy, "复制推文链接") {
                showMenuSheet = false
                copyTweetLink()
            }
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.HorizontalDivider(color = XColors.Border)
            Spacer(Modifier.height(4.dp))
            SheetAction(Icons.Filled.Delete, "删除视频", danger = true) {
                showMenuSheet = false
                confirmDelete = true
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    // P-05：删除确认——写明会清理已保存到相册的文件，主操作红色
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = XColors.Surface,
            titleContentColor = XColors.Text,
            textContentColor = Color(0xFFCFD3D7),
            title = { Text("删除这条视频？") },
            text = { Text("将从记录中删除，并清理已保存到相册的文件。\n\n此操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        app.downloadManager.deleteRecord(recordId)
                        onBack()
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = XColors.Red),
                ) { Text("删除", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = XColors.Text2) }
            },
        )
    }
}

/** 底部菜单动作行（P-01 sheet 用） */
@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (danger) XColors.Red else XColors.Text2,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            fontSize = 14.5.sp,
            color = if (danger) XColors.Red else XColors.Text,
        )
    }
}
