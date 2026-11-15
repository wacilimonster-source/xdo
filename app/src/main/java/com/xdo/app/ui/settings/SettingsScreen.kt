package com.xdo.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.xdo.app.update.UpdateManager
import com.xdo.app.update.UpdateState
import com.xdo.app.ui.components.XSnackbarHost
import com.xdo.app.ui.theme.XColors

private val QUALITY_LABELS = listOf(
    "智能（最高可用）",
    "1440p",
    "1080p",
    "720p",
    "480p",
    "360p",
    "240p",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val defaultQualityIndex by vm.defaultQualityIndex.collectAsState()
    val wifiOnly by vm.wifiOnly.collectAsState()
    val recordCount by vm.recordCount.collectAsState()
    val snack by vm.snack.collectAsState()
    val updateState by UpdateManager.state.collectAsState()
    val context = LocalContext.current

    var showQualityPicker by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(snack) {
        snack?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeSnack()
        }
    }

    Scaffold(
        containerColor = XColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = XColors.Text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = XColors.Bg),
            )
        },
        snackbarHost = { XSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // S-01：分组卡片化（X 列表风格）
            SettingGroup("下载") {
                SettingRow(
                    title = "默认清晰度",
                    subtitle = "每次解析时仍可单独选择",
                    value = QUALITY_LABELS.getOrElse(defaultQualityIndex.coerceAtLeast(0)) { "智能" },
                    showChevron = true,
                    onClick = { showQualityPicker = true },
                )
                HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF20242A))
                SettingRow(
                    title = "仅 WiFi 下载",
                    subtitle = "移动网络下载前会先询问",
                    onClick = { vm.setWifiOnly(!wifiOnly) },
                    trailing = {
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = { vm.setWifiOnly(it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = XColors.Blue,
                                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                                uncheckedTrackColor = XColors.Surface2,
                                uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                                uncheckedBorderColor = XColors.Border,
                            ),
                        )
                    },
                )
            }

            SettingGroup("数据") {
                SettingRow(
                    title = "清空历史记录",
                    subtitle = "清空列表，不删除已下载的视频文件",
                    showChevron = true,
                    onClick = { confirmClear = true },
                )
            }

            SettingGroup("版本") {
                // S-03：整行可点检查更新，副标题显示状态（去掉"可点无反应"的版本行）
                SettingRow(
                    title = "检查更新",
                    subtitle = null,
                    onClick = {
                        if (updateState != UpdateState.Checking) UpdateManager.checkNow(force = true)
                    },
                    trailing = {
                        when (updateState) {
                            is UpdateState.Available -> Text(
                                "发现新版本",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = XColors.Blue,
                            )
                            UpdateState.Checking -> Text(
                                "检查中…",
                                fontSize = 12.sp,
                                color = XColors.Text2,
                            )
                            UpdateState.CheckFailed -> Text(
                                "检查失败，点此重试",
                                fontSize = 12.sp,
                                color = XColors.Red,
                            )
                            UpdateState.UpToDate -> Text(
                                "已是最新",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = XColors.Green,
                            )
                            UpdateState.Idle -> Text(
                                "当前 v${vm.versionName}",
                                fontSize = 12.sp,
                                color = XColors.Text2,
                            )
                            else -> Unit
                        }
                    },
                )
                HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF20242A))
                SettingRow(
                    title = "GitHub 发布页",
                    subtitle = "历史版本与更新说明",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wacilimonster-source/xdo/releases")),
                            )
                        }
                    },
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            null,
                            tint = XColors.Text2,
                            modifier = Modifier.width(16.dp),
                        )
                    },
                )
            }

            SettingGroup("关于") {
                Text(
                    "在 X 中分享链接即可下载视频，支持断点续传与多清晰度选择。下载仅限公开内容，请尊重创作者版权。所有数据仅保存在本机。",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = XColors.Text2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // S-02：默认清晰度 → Bottom Sheet（点选即生效，「完成」关闭）
    if (showQualityPicker) {
        ModalBottomSheet(
            onDismissRequest = { showQualityPicker = false },
            sheetState = sheetState,
            containerColor = XColors.Surface,
            contentColor = XColors.Text,
        ) {
            Text(
                "默认清晰度",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Text(
                "开始下载时默认使用的清晰度，每次解析时仍可单独选择",
                fontSize = 12.5.sp,
                color = XColors.Text2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            // AppPrefs.defaultQualityIndex 未设置时为 -1，按「智能」处理（与解析页 pickDefaultIndex 一致）
            val normalizedIndex = if (defaultQualityIndex < 0) 0 else defaultQualityIndex
            QUALITY_LABELS.forEachIndexed { index, label ->
                val selected = index == normalizedIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.setDefaultQuality(index)
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            null,
                            tint = XColors.Blue,
                            modifier = Modifier
                                .width(22.dp),
                        )
                        Text(
                            label,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = XColors.Blue,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .weight(1f),
                        )
                    } else {
                        Spacer(Modifier.width(22.dp))
                        Text(
                            label,
                            fontSize = 14.5.sp,
                            color = XColors.Text,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .weight(1f),
                        )
                    }
                    if (selected) {
                        Text("当前", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = XColors.Blue)
                    }
                }
            }
            Button(
                onClick = {
                    showQualityPicker = false
                    scope.launch {
                        snackbarHostState.showSnackbar("默认清晰度已设为 ${QUALITY_LABELS[normalizedIndex]}")
                    }
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = XColors.Blue),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) { Text("完成", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(14.dp))
        }
    }

    // S-04：清空历史确认——写明数量与后果，主操作红色
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = XColors.Surface,
            titleContentColor = XColors.Text,
            textContentColor = androidx.compose.ui.graphics.Color(0xFFCFD3D7),
            title = { Text("清空全部下载记录？") },
            text = {
                Text(
                    "将删除 ${recordCount} 条下载记录。\n\n已下载的视频文件会保留，不会从相册删除。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        vm.clearHistory {}
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = XColors.Red),
                ) { Text("清空", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消", color = XColors.Text2) }
            },
        )
    }
}

/** 分组卡片：圆角 16 Surface + 内部行由分隔线切开 */
@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = XColors.Text2,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = XColors.Surface,
            border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0x142F3336)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit = {},
    value: String? = null,
    showChevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = XColors.Text2,
                )
            }
        }
        value?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, fontSize = 13.sp, color = XColors.Text2)
        }
        trailing?.invoke()
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = XColors.Text2,
                modifier = Modifier.width(16.dp),
            )
        }
    }
}
