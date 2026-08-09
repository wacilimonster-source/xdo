package com.xdo.app.ui.resolve

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xdo.app.data.RecordStatus
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.dl.AppUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    viewModel: ResolveViewModel,
    recordId: Long,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val record by viewModel.record.collectAsState()
    val qualities by viewModel.qualities.collectAsState()
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val downloading by viewModel.downloading.collectAsState()
    val message by viewModel.message.collectAsState()

    var confirmMetered by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.load(recordId)
    }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    fun onDownloadClick() {
        val needConfirm = AppPrefsHolder.get(context).wifiOnly && AppUtil.isMetered(context)
        if (needConfirm) confirmMetered = true
        else viewModel.startDownload(force = false) { onDone() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择清晰度") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val rec = record
        when {
            rec == null -> CenteredBox(padding) { Text("记录不存在") }

            rec.status == RecordStatus.PARSING -> CenteredBox(padding) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("解析中…")
            }

            rec.status == RecordStatus.FAILED -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(rec.errorMsg ?: "解析失败", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    if (!rec.chosenUrl.isNullOrBlank()) {
                        Button(onClick = { onDownloadClick() }) {
                            Text("重试下载")
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.resolveAgain(rec.id) }) {
                            Text("重新解析")
                        }
                    } else {
                        Button(onClick = { viewModel.resolveAgain(rec.id) }) {
                            Text("重新解析")
                        }
                    }
                }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Hero(rec.posterUrl, rec.authorName, rec.handle, rec.text, rec.durationMs)
                }
                itemsIndexed(qualities) { index, q ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (index == selectedIndex)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.select(index) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = index == selectedIndex,
                                onClick = { viewModel.select(index) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(q.label, style = MaterialTheme.typography.bodyLarge)
                                if (q.bitrateKbps > 0) {
                                    Text(
                                        "码率 ${q.bitrateKbps} kbps",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onDownloadClick() },
                        enabled = !downloading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        if (downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Filled.Download, null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (downloading) "下载中…" else "下载")
                    }
                }
            }
        }
    }

    if (confirmMetered) {
        AlertDialog(
            onDismissRequest = { confirmMetered = false },
            title = { Text("使用移动网络下载？") },
            text = { Text("当前为移动网络，下载将消耗流量。是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmMetered = false
                    viewModel.startDownload(force = true) { onDone() }
                }) {
                    Text("继续下载")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMetered = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CenteredBox(
    padding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

@Composable
private fun Hero(
    posterUrl: String?,
    authorName: String,
    handle: String,
    text: String,
    durationMs: Long?,
) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                )
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "@$handle",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text.ifBlank { authorName },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}