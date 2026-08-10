package com.xdo.app.ui.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xdo.app.update.UpdateManager
import com.xdo.app.update.UpdateState

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
    val snack by vm.snack.collectAsState()
    val updateState by UpdateManager.state.collectAsState()

    var showQualityPicker by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snack) {
        snack?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeSnack()
        }
    }

    // 更新弹窗
    val updateInfo = (updateState as? UpdateState.Available)?.info
    val updateProgress = (updateState as? UpdateState.Downloading)?.progress

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("下载")
            SettingRow(
                title = "默认清晰度",
                subtitle = QUALITY_LABELS.getOrElse(defaultQualityIndex.coerceAtLeast(0)) { "智能" },
                onClick = { showQualityPicker = true },
            )
            SettingRow(
                title = "仅 WiFi 下载",
                subtitle = "移动网络下需手动确认",
                trailing = {
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = { vm.setWifiOnly(it) },
                    )
                },
            )
            HorizontalDivider()

            SectionTitle("数据")
            SettingRow(
                title = "清空历史记录",
                subtitle = "清空列表（不删除已下载文件）",
                onClick = { confirmClear = true },
            )
            HorizontalDivider()

            // 更新检查：状态由全局 UpdateManager 维护，主界面也可看到进度
            SectionTitle("版本")
            SettingRow(
                title = "当前版本 v${vm.versionName}",
                subtitle = "",
                onClick = {},
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { UpdateManager.checkNow(force = true) },
                    enabled = updateState != UpdateState.Checking,
                ) {
                    Text(
                        when (updateState) {
                            UpdateState.Checking -> "检查中…"
                            UpdateState.CheckFailed -> "检查失败，重试"
                            else -> "检查更新"
                        }
                    )
                }
                when (updateState) {
                    UpdateState.UpToDate -> Text(
                        "已是最新版本",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    else -> Unit
                }
            }
            HorizontalDivider()

            SectionTitle("关于")
            SettingRow(
                title = "X 下载助手",
                subtitle = "在 X 中分享链接即可下载视频，下载仅限公开内容，请尊重创作者版权。数据全部本地保存。",
                onClick = {},
            )
        }
    }

    // 清晰度选择弹窗
    if (showQualityPicker) {
        AlertDialog(
            onDismissRequest = { showQualityPicker = false },
            title = { Text("默认清晰度") },
            text = {
                Column {
                    QUALITY_LABELS.forEachIndexed { index, label ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setDefaultQuality(index)
                                    showQualityPicker = false
                                },
                        ) {
                            RadioButton(
                                selected = index == defaultQualityIndex,
                                onClick = {
                                    vm.setDefaultQuality(index)
                                    showQualityPicker = false
                                },
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showQualityPicker = false }) { Text("关闭") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史记录？") },
            text = { Text("将清空全部下载记录（不删除已下载文件）。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearHistory {}
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    // 粘贴 Cookie 弹窗已移除：v0.1.8 起全程免登录，X 登录凭据已不再需要

    // 更新弹窗：全局 UpdateManager 状态由 AppRoot 统一展示，此处不重复弹
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        trailing?.invoke()
    }
}
