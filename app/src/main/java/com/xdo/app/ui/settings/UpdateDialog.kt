package com.xdo.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xdo.app.update.UpdateInfo
import com.xdo.app.update.UpdateState

/**
 * 全局更新弹窗：AppRoot 持有，任何页面都可看到检查结果与下载进度。
 * 下载中显示进度条 + 百分比 + 已下载/总大小。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    state: UpdateState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${info.versionName}") },
        text = {
            Column {
                Text(info.updateMessage)
                if (state is UpdateState.Downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    val sizeText = if (state.totalBytes > 0)
                        "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
                    else "${formatBytes(state.downloadedBytes)}"
                    Text(
                        "下载中 ${state.progress}% · $sizeText",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state is UpdateState.DownloadFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Downloading -> Text("下载中…")
                else -> TextButton(onClick = onDownload) { Text("立即更新") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后再说") }
        },
    )
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1fGB".format(bytes / 1073741824.0)
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}