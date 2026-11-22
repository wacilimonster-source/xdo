package com.xdo.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.xdo.app.PermissionBridge
import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.RecordStatus
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.dl.AppUtil
import com.xdo.app.dl.DownloadSpeed
import com.xdo.app.ui.components.IconCircle
import com.xdo.app.ui.components.MiniPill
import com.xdo.app.ui.components.MiniSpinner
import com.xdo.app.ui.components.QChip
import com.xdo.app.ui.components.XFilterChip
import com.xdo.app.ui.components.XSnackbarHost
import com.xdo.app.ui.components.formatBytes
import com.xdo.app.ui.components.formatDuration
import com.xdo.app.ui.components.formatEta
import com.xdo.app.ui.components.formatSpeed
import com.xdo.app.ui.components.recordTitle
import com.xdo.app.ui.components.timeLabel
import com.xdo.app.ui.theme.XColors
import com.xdo.app.util.TweetLink
import kotlinx.coroutines.launch

// 状态筛选分组（H-02）：进行中 = 解析/待选清晰度/下载中/已暂停
private val CATS = listOf(
    "all" to "全部",
    "active" to "进行中",
    "done" to "已完成",
    "failed" to "失败",
)

private fun statusGroup(status: Int): String = when (status) {
    RecordStatus.DONE -> "done"
    RecordStatus.FAILED -> "failed"
    else -> "active"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenResolve: (Long) -> Unit,
    onOpenPlayer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // P-06：collectAsStateWithLifecycle——应用退到后台时停止收集，不再为看不见的界面重组
    val records by viewModel.records.collectAsStateWithLifecycle()
    val deletingIds by viewModel.deletingIds.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboardLink.collectAsStateWithLifecycle()
    // P-06：不再在页面顶层收集 DownloadSpeed（150ms 一拍，会让整页每拍重组）；
    // 改由每张下载中卡片的状态行各自订阅自己那一条，见 DownloadingStatus
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var fileDeletedRecord by remember { mutableStateOf<DownloadRecord?>(null) }
    // B21：仅 WiFi 下点「继续」先确认是否使用流量
    var confirmResume by remember { mutableStateOf<DownloadRecord?>(null) }
    // H-10：通知权限前置说明（首启一次）
    var showNotifPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.checkClipboard()
        viewModel.maybeBackfillMeta()
        viewModel.snack.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        val prefs = AppPrefsHolder.get(context)
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.notifPromptAnswered) showNotifPrompt = true
    }

    /** H-05：删除 = 立即从列表消失 + 底部 Snackbar 可撤销（5s 后才真正删库删文件） */
    fun requestUndoDelete(record: DownloadRecord) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.deleteRecordWithUndo(record)
        scope.launch {
            val r = snackbarHostState.showSnackbar(
                "已删除「${recordTitle(record)}」",
                actionLabel = "撤销",
                duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) viewModel.undoDelete(record.id)
        }
    }

    fun handleCardClick(record: DownloadRecord) {
        when (record.status) {
            RecordStatus.DONE -> {
                // B26：文件检查移到后台线程，不再阻塞点击回调
                viewModel.isFileDeletedAsync(record) { deleted ->
                    if (deleted) fileDeletedRecord = record
                    else onOpenPlayer(record.id)
                }
            }
            RecordStatus.READY,
            RecordStatus.PARSING,
            RecordStatus.FAILED,
            -> onOpenResolve(record.id)
            else -> scope.launch {
                snackbarHostState.showSnackbar("任务进行中，可直接在卡片上暂停 / 继续")
            }
        }
    }

    val aliveRecords = remember(records, deletingIds) {
        records.filter { it.id !in deletingIds }
    }
    val counts = remember(aliveRecords) {
        mapOf(
            "all" to aliveRecords.size,
            "active" to aliveRecords.count { statusGroup(it.status) == "active" },
            "done" to aliveRecords.count { statusGroup(it.status) == "done" },
            "failed" to aliveRecords.count { statusGroup(it.status) == "failed" },
        )
    }
    val filtered = remember(aliveRecords, search, filter) {
        aliveRecords
            .filter { r ->
                search.isBlank() || r.text.contains(search, true) ||
                    r.handle.contains(search, true) ||
                    r.authorName.contains(search, true)
            }
            .filter { r -> filter == "all" || statusGroup(r.status) == filter }
    }
    val isLink = remember(search) { TweetLink.looksLikeTweetText(search) }

    Scaffold(
        containerColor = XColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("X 下载助手", fontSize = 19.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = XColors.Bg),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置", tint = XColors.Text)
                    }
                },
            )
        },
        snackbarHost = { XSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SearchField(
                value = search,
                onChange = { search = it },
                isLink = isLink,
                onResolve = {
                    val text = search
                    search = ""
                    viewModel.paste(text)
                },
            )

            if (aliveRecords.isNotEmpty()) {
                // H-02：状态筛选 chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CATS.forEach { (key, label) ->
                        XFilterChip(
                            label = label,
                            count = counts[key] ?: 0,
                            selected = filter == key,
                            onClick = { filter = key },
                        )
                    }
                }
            }

            // H-07：剪贴板检测条
            AnimatedVisibility(visible = clipboard != null) {
                Surface(
                    color = XColors.BlueDim,
                    border = androidx.compose.foundation.BorderStroke(1.dp, XColors.Blue.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Link,
                            null,
                            tint = XColors.Blue,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "检测到剪贴板中的 X 链接",
                            color = Color(0xFFA8D5F5),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        MiniPill("解析", filled = true) { clipboard?.let { viewModel.paste(it) } }
                        IconButton(onClick = { viewModel.dismissClipboard() }) {
                            Icon(Icons.Filled.Close, "忽略", tint = XColors.Text2, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }

            when {
                // 链接态不显示"搜索无结果"，给解析指引
                isLink && filtered.isEmpty() -> LinkInputHint()
                filtered.isEmpty() && search.isNotBlank() -> EmptySearchResult(
                    keyword = search,
                    onClear = { search = "" },
                )
                filtered.isEmpty() -> EmptyGuide()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { record ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.StartToEnd) {
                                    requestUndoDelete(record)
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = true,
                            enableDismissFromEndToStart = false,
                            backgroundContent = {
                                // H-05：X 风格红色 15% 底 + 删除图标
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(XColors.RedDim),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        "删除",
                                        tint = XColors.Red,
                                        modifier = Modifier.padding(start = 20.dp).size(22.dp),
                                    )
                                }
                            },
                        ) {
                            RecordCard(
                                record = record,
                                onClick = { handleCardClick(record) },
                                onContinueRequest = { rec ->
                                    val needConfirm =
                                        AppPrefsHolder.get(context).wifiOnly &&
                                            AppUtil.isMetered(context)
                                    if (needConfirm) confirmResume = rec
                                    else viewModel.startOrResume(rec)
                                },
                                onDelete = { requestUndoDelete(record) },
                                viewModel = viewModel,
                                onPlay = { handleCardClick(record) },
                                onOpenResolve = { onOpenResolve(record.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 本地文件被删除时弹窗：可重新下载或删除记录（后果写明，主操作=重新下载）
    fileDeletedRecord?.let { rec ->
        AlertDialog(
            onDismissRequest = { fileDeletedRecord = null },
            containerColor = XColors.Surface,
            titleContentColor = XColors.Text,
            textContentColor = Color(0xFFCFD3D7),
            title = { Text("视频文件已丢失") },
            text = { Text("「${recordTitle(rec)}」的本地文件已被删除或被系统清理，无法播放。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.reprepareDownload(rec)
                        fileDeletedRecord = null
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = XColors.Blue),
                ) { Text("重新下载") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRecord(rec)
                        fileDeletedRecord = null
                    },
                ) { Text("删除记录", color = XColors.Red) }
            },
        )
    }

    // B21：仅 WiFi 开启且当前为移动网络时，「继续」先确认是否消耗流量
    confirmResume?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmResume = null },
            containerColor = XColors.Surface,
            titleContentColor = XColors.Text,
            textContentColor = Color(0xFFCFD3D7),
            title = { Text("使用移动网络下载？") },
            text = { Text("当前为移动网络且已开启「仅 WiFi 下载」。继续下载将消耗流量。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmResume = null
                        viewModel.startOrResume(rec, force = true)
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = XColors.Blue),
                ) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResume = null }) { Text("取消", color = XColors.Text2) }
            },
        )
    }

    // H-10：通知权限前置说明（替代裸系统弹窗；问过一次后不再打扰）
    if (showNotifPrompt) {
        AlertDialog(
            onDismissRequest = { showNotifPrompt = false },
            containerColor = XColors.Surface,
            titleContentColor = XColors.Text,
            textContentColor = Color(0xFFCFD3D7),
            title = { Text("开启通知") },
            text = {
                Text("下载进度与完成提醒需要通知权限。\n\n不开启也可以正常下载，只是无法在通知栏看到进度。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        AppPrefsHolder.get(context).notifPromptAnswered = true
                        showNotifPrompt = false
                        PermissionBridge.requestNotif?.invoke()
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = XColors.Blue),
                ) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppPrefsHolder.get(context).notifPromptAnswered = true
                    showNotifPrompt = false
                }) { Text("暂不", color = XColors.Text2) }
            },
        )
    }
}

// ------------------------------------------------------------
// H-01：搜索/解析双模式输入框
// ------------------------------------------------------------

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    isLink: Boolean,
    onResolve: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = {
            Text("搜索记录，或粘贴 X 链接", color = XColors.Text2, fontSize = 13.sp)
        },
        leadingIcon = {
            Icon(Icons.Filled.Search, null, tint = XColors.Text2, modifier = Modifier.size(19.dp))
        },
        trailingIcon = {
            when {
                isLink -> MiniPill("解析", filled = true, onClick = onResolve)
                value.isNotEmpty() -> IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, "清除", tint = XColors.Text2, modifier = Modifier.size(16.dp))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = XColors.Surface,
            unfocusedContainerColor = XColors.Surface,
            focusedBorderColor = if (isLink) XColors.Blue else XColors.Border,
            unfocusedBorderColor = if (isLink) XColors.Blue.copy(alpha = 0.7f) else XColors.Border,
            cursorColor = XColors.Blue,
            focusedTextColor = XColors.Text,
            unfocusedTextColor = XColors.Text,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = XColors.Text),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(52.dp),
    )
}

// ------------------------------------------------------------
// H-08：空状态（无记录=三步引导；搜索无结果=清除入口）
// ------------------------------------------------------------

@Composable
private fun EmptyGuide() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconCircle(Icons.Filled.VideoLibrary)
        Spacer(Modifier.height(14.dp))
        Text("还没有下载记录", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "在 X 里看到喜欢的视频，三步就能存到本地",
            fontSize = 13.sp,
            color = XColors.Text2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        GuideStep(1, "打开 X 中的视频推文", "点推文右上角「分享」")
        Spacer(Modifier.height(10.dp))
        GuideStep(2, "在分享菜单选择「X 下载助手」", "自动识别链接并解析清晰度")
        Spacer(Modifier.height(10.dp))
        GuideStep(3, "选择清晰度开始下载", "支持断点续传，完成后自动保存到相册")
    }
}

@Composable
private fun GuideStep(n: Int, title: String, sub: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = XColors.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(XColors.BlueDim),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    n.toString(),
                    color = XColors.Blue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 13.sp, lineHeight = 18.sp)
                Text(sub, fontSize = 12.sp, color = XColors.Text2, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun LinkInputHint() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Link,
            null,
            tint = XColors.Blue,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("检测到 X 链接", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "点上方「解析」或按回车开始下载",
            fontSize = 13.sp,
            color = XColors.Text2,
        )
    }
}

@Composable
private fun EmptySearchResult(keyword: String, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Search,
            null,
            tint = XColors.Text2,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "没有匹配「${keyword.take(12)}」的记录",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "试试其他关键词，或直接粘贴 X 链接解析",
            fontSize = 13.sp,
            color = XColors.Text2,
        )
        Spacer(Modifier.height(16.dp))
        MiniPill("清除搜索", onClick = onClear)
    }
}

// ------------------------------------------------------------
// H-04：记录卡片（16:9 缩略图 + 时长徽章 + 内联状态操作）
// ------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordCard(
    record: DownloadRecord,
    onClick: () -> Unit,
    onContinueRequest: (DownloadRecord) -> Unit,
    onDelete: () -> Unit,
    viewModel: HomeViewModel,
    onPlay: () -> Unit,
    onOpenResolve: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = XColors.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true }),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Thumbnail(record)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(IntrinsicSize.Min),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        recordTitle(record),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                "更多",
                                tint = XColors.Text2,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        MoreMenu(
                            record = record,
                            viewModel = viewModel,
                            onContinueRequest = onContinueRequest,
                            onDelete = onDelete,
                            onPlay = onPlay,
                            expanded = menuExpanded,
                            onExpandedChange = { menuExpanded = it },
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    metaLine(record),
                    fontSize = 12.sp,
                    color = XColors.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                StatusRow(record, viewModel, onContinueRequest, onOpenResolve)
            }
        }
    }
}

/** 作者行 · @handle · 时间（完成记录显示完成时间） */
private fun metaLine(record: DownloadRecord): String {
    val ts = if (record.status == RecordStatus.DONE)
        record.completedAt ?: record.createdAt
    else record.createdAt
    return listOfNotNull(
        record.authorName.takeIf { it.isNotBlank() },
        "@${record.handle}".takeIf { record.handle.isNotBlank() },
        timeLabel(ts),
    ).joinToString(" · ")
}

@Composable
private fun Thumbnail(record: DownloadRecord) {
    Box(
        modifier = Modifier
            .size(width = 112.dp, height = 63.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(XColors.Surface2),
    ) {
        SubcomposeAsyncImage(
            model = record.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { ThumbnailPlaceholder() },
            error = { ThumbnailPlaceholder() },
            success = { SubcomposeAsyncImageContent() },
        )
        // 中央播放浮层
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(26.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x8C000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        // 右下角时长徽章
        record.durationMs?.takeIf { it > 0 }?.let { ms ->
            Text(
                formatDuration(ms),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xBF000000))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ThumbnailPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.PlayArrow,
            null,
            tint = XColors.Text2,
            modifier = Modifier.size(24.dp),
        )
    }
}

// ------------------------------------------------------------
// H-03：状态行 + 内联操作（暂停/取消/继续/重试一步直达）
// ------------------------------------------------------------

@Composable
private fun StatusRow(
    record: DownloadRecord,
    viewModel: HomeViewModel,
    onContinueRequest: (DownloadRecord) -> Unit,
    onOpenResolve: () -> Unit,
) {
    when (record.status) {
        RecordStatus.PARSING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniSpinner()
                Spacer(Modifier.width(8.dp))
                Text("正在解析…", fontSize = 12.5.sp, color = XColors.Text2)
            }
        }
        RecordStatus.READY -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "待选择清晰度",
                    fontSize = 12.5.sp,
                    color = XColors.Blue,
                    modifier = Modifier.weight(1f),
                )
                MiniPill("继续", onClick = onOpenResolve)
            }
        }
        RecordStatus.DOWNLOADING -> DownloadingStatus(record, viewModel)
        RecordStatus.PAUSED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "已暂停 · ${record.progress}%",
                        fontSize = 12.5.sp,
                        color = XColors.Yellow,
                    )
                    // T-01：系统自动暂停（后台时长上限等）时说明原因；用户手动暂停时 errorMsg 为空
                    record.errorMsg?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                            reason,
                            fontSize = 11.5.sp,
                            color = XColors.Text2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                MiniPill("继续") { onContinueRequest(record) }
            }
        }
        RecordStatus.FAILED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    null,
                    tint = XColors.Red,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    record.errorMsg ?: "下载失败",
                    fontSize = 12.5.sp,
                    color = XColors.Red,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // R-03 语义拆分：已有下载地址 → 重试下载；否则 → 重新解析
                MiniPill(
                    if (record.chosenUrl.isNullOrBlank()) "重新解析" else "重试",
                ) {
                    if (record.chosenUrl.isNullOrBlank()) viewModel.retryResolve(record)
                    else viewModel.startOrResume(record)
                }
            }
        }
        RecordStatus.DONE -> {
            // P-05：DONE 且带 errorMsg = HLS 音轨没能合成进去（只保存了画面），给出明确提示
            val audioMissing = !record.errorMsg.isNullOrBlank()
            Row(verticalAlignment = Alignment.CenterVertically) {
                QChip(record.chosenLabel ?: "视频")
                Spacer(Modifier.width(8.dp))
                Text(
                    if (audioMissing) {
                        "${formatBytes(record.fileSize ?: 0)} · 已保存 · 无声音"
                    } else {
                        "${formatBytes(record.fileSize ?: 0)} · 已保存"
                    },
                    fontSize = 12.sp,
                    color = if (audioMissing) XColors.Yellow else XColors.Text2,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (audioMissing) Icons.AutoMirrored.Filled.VolumeOff else Icons.Filled.Check,
                    null,
                    tint = if (audioMissing) XColors.Yellow else XColors.Green,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/**
 * P-06：下载中状态行——只有这里订阅本任务的实时进度/网速。
 * 150ms 一拍的更新只重组这一行（进度条 + 文字），卡片其余部分、其他卡片和整个首页都不动。
 */
@Composable
private fun DownloadingStatus(record: DownloadRecord, viewModel: HomeViewModel) {
    val infoFlow = remember(record.id) { DownloadSpeed.infoFlow(record.id) }
    val speed by infoFlow.collectAsStateWithLifecycle(initialValue = DownloadSpeed.infoOf(record.id))
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            // P-01：优先用内存实时进度（DB 只 ~1s 落库一次）
            progress = { (speed?.progress ?: record.progress) / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = XColors.Blue,
            trackColor = XColors.Border,
            strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                downloadingLabel(record, speed),
                fontSize = 12.sp,
                color = XColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { viewModel.pause(record) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Filled.Pause, "暂停",
                    tint = XColors.Text2, modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = { viewModel.cancel(record) }, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close, "取消",
                    tint = XColors.Text2, modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 下载中标签：「68% · 2.4MB/s · 剩余 8s」（速度仅 MP4 直链可算，HLS 显示百分比） */
private fun downloadingLabel(record: DownloadRecord, speed: DownloadSpeed.Info?): String =
    buildString {
        append("${speed?.progress ?: record.progress}%")
        // Info 里现在可能只有进度没有速度（如 HLS），速度为 0 时不显示「0B/s」
        speed?.takeIf { it.bytesPerSec > 0 }?.let {
            append(" · ${formatSpeed(it.bytesPerSec)}")
            it.etaSec?.let { eta -> append(" · ${formatEta(eta)}") }
        }
    }

// ------------------------------------------------------------
// H-06：三点菜单（按状态重组，图标语义修正，删除红色置底）
// ------------------------------------------------------------

@Composable
private fun MoreMenu(
    record: DownloadRecord,
    viewModel: HomeViewModel,
    onContinueRequest: (DownloadRecord) -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    fun close() = onExpandedChange(false)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { onExpandedChange(false) },
    ) {
        when (record.status) {
            RecordStatus.DOWNLOADING -> {
                MenuAction("暂停", Icons.Filled.Pause) { viewModel.pause(record) }
                MenuAction("取消下载", Icons.Filled.Close) { viewModel.cancel(record) }
                MenuAction("复制链接", Icons.Filled.ContentCopy) { viewModel.copyLink(record) }
                MenuDividerRow()
                MenuAction("删除", Icons.Filled.Delete, danger = true) { onDelete() }
            }
            RecordStatus.PAUSED -> {
                MenuAction("继续下载", Icons.Filled.PlayArrow) { onContinueRequest(record) }
                MenuAction("取消下载", Icons.Filled.Close) { viewModel.cancel(record) }
                MenuAction("复制链接", Icons.Filled.ContentCopy) { viewModel.copyLink(record) }
                MenuDividerRow()
                MenuAction("删除", Icons.Filled.Delete, danger = true) { onDelete() }
            }
            RecordStatus.FAILED, RecordStatus.READY, RecordStatus.PARSING -> {
                MenuAction("重新解析", Icons.Filled.Refresh) { viewModel.retryResolve(record) }
                MenuAction("复制链接", Icons.Filled.ContentCopy) { viewModel.copyLink(record) }
                MenuDividerRow()
                MenuAction("删除", Icons.Filled.Delete, danger = true) { onDelete() }
            }
            RecordStatus.DONE -> {
                MenuAction("播放", Icons.Filled.PlayArrow) { onPlay() }
                MenuAction("分享文件", Icons.Filled.Share) { viewModel.shareFile(record) }
                MenuAction("复制链接", Icons.Filled.ContentCopy) { viewModel.copyLink(record) }
                MenuAction("重新下载", Icons.Filled.Download) { viewModel.reprepareDownload(record) }
                MenuDividerRow()
                MenuAction("删除", Icons.Filled.Delete, danger = true) { onDelete() }
            }
        }
    }
}

/** 菜单内分隔线（material3 1.2.1 无 MenuDivider，用 HorizontalDivider 实现） */
@Composable
private fun MenuDividerRow() {
    HorizontalDivider(
        color = XColors.Border,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun MenuAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                fontSize = 13.5.sp,
                color = if (danger) XColors.Red else XColors.Text,
            )
        },
        leadingIcon = {
            Icon(icon, null, tint = if (danger) XColors.Red else XColors.Text2)
        },
        onClick = onClick,
    )
}
