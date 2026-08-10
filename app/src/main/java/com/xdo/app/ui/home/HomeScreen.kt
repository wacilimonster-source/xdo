package com.xdo.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xdo.app.R
import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.RecordStatus
import com.xdo.app.util.TweetLink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenResolve: (Long) -> Unit,
    onOpenPlayer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val records by viewModel.records.collectAsState()
    val clipboard by viewModel.clipboardLink.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.checkClipboard()
        viewModel.snack.collect { snackbarHostState.showSnackbar(it) }
    }

    val filtered = remember(records, search) {
        if (search.isBlank()) records
        else records.filter {
            it.text.contains(search, true) ||
                it.handle.contains(search, true) ||
                it.authorName.contains(search, true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("X 下载助手") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SearchBar(
                value = search,
                onChange = { search = it },
                showResolveButton = TweetLink.looksLikeTweetText(search),
                onResolve = {
                    val text = search
                    search = ""
                    viewModel.paste(text)
                },
            )
            clipboard?.let { text ->
                ClipboardBanner(
                    onClick = { viewModel.paste(text) },
                    onDismiss = { viewModel.dismissClipboard() },
                )
            }
            if (filtered.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filtered, key = { it.id }) { record ->
                        RecordCard(
                            record = record,
                            onClick = {
                                when (record.status) {
                                    RecordStatus.DONE -> onOpenPlayer(record.id)
                                    RecordStatus.READY,
                                    RecordStatus.PARSING,
                                    RecordStatus.FAILED,
                                    -> onOpenResolve(record.id)
                                    else -> Unit
                                }
                            },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    value: String,
    onChange: (String) -> Unit,
    showResolveButton: Boolean,
    onResolve: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text("搜索标题 / 作者，或粘贴 X 链接解析") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (showResolveButton) {
                    IconButton(onClick = onResolve) {
                        Icon(Icons.Filled.Download, "解析链接")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ClipboardBanner(onClick: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Share, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                "检测到剪贴板中的 X 链接，点此解析",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Cancel, "忽略", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Download,
            null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "还没有下载记录",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "去 X 里对视频点「分享」→ 选择本应用即可下载",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun RecordCard(
    record: DownloadRecord,
    onClick: () -> Unit,
    viewModel: HomeViewModel,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Poster(record)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(IntrinsicSize.Min),
            ) {
                Text(
                    record.text.ifBlank { record.authorName.ifBlank { "X 视频" } },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val duration = record.durationMs?.let { formatDuration(it) }
                Text(
                    buildString {
                        append("@${record.handle.ifBlank { "unknown" }}")
                        if (!duration.isNullOrBlank()) append(" · $duration")
                        if (!record.chosenLabel.isNullOrBlank()) append(" · ${record.chosenLabel}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                StatusRow(record, viewModel)
            }
            Spacer(Modifier.width(8.dp))
            MoreMenu(record, viewModel)
        }
    }
}

@Composable
private fun Poster(record: DownloadRecord) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val poster = record.posterUrl
        if (!poster.isNullOrBlank()) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.PlayArrow,
                    null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusRow(record: DownloadRecord, viewModel: HomeViewModel) {
    when (record.status) {
        RecordStatus.PARSING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text("解析中…", style = MaterialTheme.typography.bodySmall)
            }
        }
        RecordStatus.READY -> {
            Text(
                "已解析 · 点击选择清晰度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        RecordStatus.DOWNLOADING -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { record.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "下载中 ${record.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        RecordStatus.PAUSED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已暂停 · ${record.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                TextButton(onClick = { viewModel.startOrResume(record) }) {
                    Text("继续")
                }
            }
        }
        RecordStatus.FAILED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.errorMsg ?: "下载失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RecordStatus.DONE -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatSize(record.fileSize ?: 0) + " · " + dateLabel(record.completedAt ?: record.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun MoreMenu(record: DownloadRecord, viewModel: HomeViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, "更多")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            when (record.status) {
                RecordStatus.DOWNLOADING -> {
                    DropdownMenuItem(
                        text = { Text("暂停") },
                        onClick = {
                            expanded = false
                            viewModel.pause(record)
                        },
                        leadingIcon = { Icon(Icons.Filled.Pause, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("取消") },
                        onClick = {
                            expanded = false
                            viewModel.cancel(record)
                        },
                        leadingIcon = { Icon(Icons.Filled.Cancel, null) },
                    )
                }
                RecordStatus.PAUSED -> {
                    DropdownMenuItem(
                        text = { Text("继续下载") },
                        onClick = {
                            expanded = false
                            viewModel.startOrResume(record)
                        },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("取消") },
                        onClick = {
                            expanded = false
                            viewModel.cancel(record)
                        },
                        leadingIcon = { Icon(Icons.Filled.Cancel, null) },
                    )
                }
                RecordStatus.FAILED, RecordStatus.READY, RecordStatus.PARSING -> {
                    DropdownMenuItem(
                        text = { Text("重试") },
                        onClick = {
                            expanded = false
                            viewModel.retryResolve(record)
                        },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    )
                }
                RecordStatus.DONE -> {
                    DropdownMenuItem(
                        text = { Text("分享文件") },
                        onClick = {
                            expanded = false
                            viewModel.shareFile(record)
                        },
                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text("复制链接") },
                onClick = {
                    expanded = false
                    viewModel.copyLink(record)
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    expanded = false
                    viewModel.deleteRecord(record)
                },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1fGB".format(bytes / 1073741824.0)
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

private fun dateLabel(ts: Long): String =
    SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))