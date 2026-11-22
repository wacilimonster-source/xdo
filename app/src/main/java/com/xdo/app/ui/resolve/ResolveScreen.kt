package com.xdo.app.ui.resolve

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xdo.app.data.DownloadRecord
import com.xdo.app.data.QualityOption
import com.xdo.app.data.RecordStatus
import com.xdo.app.dl.AppPrefsHolder
import com.xdo.app.dl.AppUtil
import com.xdo.app.ui.components.DefBadge
import com.xdo.app.ui.components.FmtBadge
import com.xdo.app.ui.components.MiniSpinner
import com.xdo.app.ui.components.formatBytes
import com.xdo.app.ui.components.formatDuration
import com.xdo.app.ui.theme.XColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    viewModel: ResolveViewModel,
    recordId: Long,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val record by viewModel.record.collectAsStateWithLifecycle()
    val qualities by viewModel.qualities.collectAsStateWithLifecycle()
    val selectedIndex by viewModel.selectedIndex.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

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
        containerColor = XColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("选择清晰度", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = XColors.Text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = XColors.Bg),
            )
        },
        snackbarHost = { com.xdo.app.ui.components.XSnackbarHost(snackbarHostState) },
        bottomBar = {
            // R-02：底部固定下载栏——所选清晰度与预估大小始终可见
            val rec = record
            if (rec != null && rec.status != RecordStatus.PARSING && rec.status != RecordStatus.FAILED) {
                Surface(
                    color = XColors.Bg.copy(alpha = 0.92f),
                    border = BorderStroke(0.dp, Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .background(Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val q = qualities.getOrNull(selectedIndex)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (q != null) "已选 ${q.label}" else "选择清晰度",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = XColors.Text,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (q != null) "${estimatedSize(q, rec)?.let { "约 $it · " } ?: ""}开始后可暂停/续传"
                                else "在列表中选择一个清晰度",
                                fontSize = 12.sp,
                                color = XColors.Text2,
                            )
                        }
                        Button(
                            onClick = { onDownloadClick() },
                            enabled = !downloading && q != null,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = XColors.Blue,
                                contentColor = Color.White,
                                disabledContainerColor = XColors.Surface2,
                                disabledContentColor = XColors.Text2,
                            ),
                            modifier = Modifier.height(46.dp),
                        ) {
                            Text(if (downloading) "下载中…" else "下载", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
    ) { padding ->
        val rec = record
        when {
            rec == null -> CenteredBox(padding) { Text("记录不存在", color = XColors.Text2) }

            rec.status == RecordStatus.PARSING -> CenteredBox(padding) {
                // R-04：明确当前动作与预期时长
                MiniSpinner(size = 30)
                Spacer(Modifier.height(14.dp))
                Text("正在获取视频信息…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("通常只需几秒，完成后可直接选择清晰度", fontSize = 12.5.sp, color = XColors.Text2)
            }

            rec.status == RecordStatus.FAILED -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        null,
                        modifier = Modifier.size(42.dp),
                        tint = XColors.Red,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("解析失败", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        rec.errorMsg ?: "该推文没有视频，或内容不可见",
                        style = MaterialTheme.typography.bodyMedium,
                        color = XColors.Text2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(22.dp))
                    // R-03：语义拆分——有下载地址 → 重试下载（主）/ 重新解析（次）；否则只有重新解析
                    if (!rec.chosenUrl.isNullOrBlank()) {
                        Button(
                            onClick = { onDownloadClick() },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = XColors.Blue),
                        ) { Text("重试下载", fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.resolveAgain(rec.id) }) {
                            Text("重新解析", color = XColors.Blue)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.resolveAgain(rec.id) },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = XColors.Blue),
                        ) { Text("重新解析", fontWeight = FontWeight.Bold) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "失败原因会区分网络 / 磁盘 / 解析，便于判断下一步",
                        fontSize = 12.sp,
                        color = XColors.Text2,
                    )
                }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Hero(rec) }
                itemsIndexed(qualities) { index, q ->
                    QualityRow(
                        option = q,
                        selected = index == selectedIndex,
                        isDefault = index == viewModel.defaultIndexFor(qualities),
                        sizeLabel = estimatedSize(q, rec),
                        onClick = { viewModel.select(index) },
                    )
                }
            }
        }
    }

    if (confirmMetered) {
        val recNow = record
        AlertDialog(
            onDismissRequest = { confirmMetered = false },
            containerColor = XColors.Surface,
            titleContentColor = XColors.Text,
            textContentColor = Color(0xFFCFD3D7),
            title = { Text("使用移动网络下载？") },
            text = {
                val q = qualities.getOrNull(selectedIndex)
                Text(
                    "当前为移动网络，且已开启「仅 WiFi 下载」。\n\n继续将消耗流量" +
                        (if (recNow != null) estimatedSize(q, recNow)?.let { "（约 $it）。" } ?: "。" else "。"),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmMetered = false
                        viewModel.startDownload(force = true) { onDone() }
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = XColors.Blue),
                ) { Text("继续下载", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmMetered = false }) { Text("取消", color = XColors.Text2) }
            },
        )
    }
}

/** R-01：码率 × 时长 预估文件大小（bitrateKbps[kb/s] × durationMs[ms] / 8 = 字节） */
private fun estimatedSize(q: QualityOption?, rec: DownloadRecord): String? {
    if (q == null) return null
    val dur = rec.durationMs ?: return null
    if (q.bitrateKbps <= 0 || dur <= 0) return null
    val bytes = q.bitrateKbps.toLong() * dur / 8
    return if (bytes > 0) formatBytes(bytes) else null
}

/** R-01：清晰度卡片（单选 + HLS/MP4 格式徽章 + 默认徽章 + 预估大小） */
@Composable
private fun QualityRow(
    option: QualityOption,
    selected: Boolean,
    isDefault: Boolean,
    sizeLabel: String?,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) XColors.BlueDim else XColors.Surface,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) XColors.Blue else XColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = XColors.Blue,
                    unselectedColor = XColors.Text2,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        option.label,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = XColors.Text,
                    )
                    // X 返回的 label 可能已带「HLS/MP4」后缀，此时不重复挂格式徽章
                    val fmt = if (option.isHls) "HLS" else "MP4"
                    if (!option.label.contains(fmt, ignoreCase = true)) {
                        Spacer(Modifier.width(6.dp))
                        FmtBadge(fmt)
                    }
                    if (isDefault) {
                        Spacer(Modifier.width(6.dp))
                        DefBadge()
                    }
                }
                if (option.bitrateKbps > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "码率 ${option.bitrateKbps} kbps",
                        fontSize = 11.5.sp,
                        color = XColors.Text2,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (sizeLabel != null) {
                    Text(
                        "≈ $sizeLabel",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = XColors.Text,
                    )
                    Text("预估大小", fontSize = 10.5.sp, color = XColors.Text2)
                }
            }
        }
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

/** R-05：封面 Hero（16:9 + 播放浮层 + 时长徽章 + 作者行 + 文案） */
@Composable
private fun Hero(rec: DownloadRecord) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = XColors.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (!rec.posterUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(XColors.Surface2),
                ) {
                    AsyncImage(
                        model = rec.posterUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(52.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    rec.durationMs?.takeIf { it > 0 }?.let { ms ->
                        Text(
                            formatDuration(ms),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xBF000000))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            if (rec.authorName.isNotBlank()) append(rec.authorName)
                            if (rec.handle.isNotBlank()) {
                                if (isNotEmpty()) append("  ")
                                append("@${rec.handle}")
                            }
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = XColors.Blue,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    rec.text.ifBlank { rec.authorName },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFCFD3D7),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
