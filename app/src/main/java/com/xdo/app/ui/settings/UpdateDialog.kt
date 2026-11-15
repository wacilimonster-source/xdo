package com.xdo.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xdo.app.update.UpdateInfo
import com.xdo.app.update.UpdateState
import com.xdo.app.ui.components.formatBytes
import com.xdo.app.ui.theme.XColors

/**
 * 全局更新弹窗：AppRoot 持有，任何页面都可看到检查结果与下载进度。
 * 三种形态（B07：Downloading/DownloadFailed 也保持弹窗）：
 * - 发现新版本：版本号 + 更新内容 + [稍后再说 / 立即更新]
 * - 下载中：进度条 + 百分比 + 已下载/总大小 + [取消下载]（真实取消后台协程）
 * - 失败：红色原因 + [稍后再说 / 重试]
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    state: UpdateState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = XColors.Surface,
        titleContentColor = XColors.Text,
        textContentColor = Color(0xFFCFD3D7),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                when (state) {
                    is UpdateState.Downloading -> "正在下载更新 v${info.versionName}"
                    is UpdateState.DownloadFailed -> "更新下载失败"
                    is UpdateState.InstallBlocked -> "需要开启安装权限"
                    else -> "新版本 v${info.versionName}"
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                if (state is UpdateState.InstallBlocked) {
                    // 安装权限引导：写清下一步发生什么（跳设置开关页 → 回来自动续装，不重下）
                    Text("安装新版本需要允许本应用「安装未知应用」权限。")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点击「去开启」会直接跳到系统设置中的开关页；开启后回到应用会自动继续安装，已下载的安装包不会作废。",
                        style = Material3Small,
                        color = XColors.Text2,
                    )
                } else {
                    if (state !is UpdateState.Downloading) {
                        Text(info.updateMessage)
                    }
                }
                if (state is UpdateState.Downloading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = XColors.Blue,
                        trackColor = XColors.Border,
                    )
                    Spacer(Modifier.height(6.dp))
                    val sizeText = if (state.totalBytes > 0)
                        "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
                    else formatBytes(state.downloadedBytes)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${state.progress}% · $sizeText",
                            style = Material3Small,
                            color = XColors.Text2,
                        )
                    }
                }
                if (state is UpdateState.DownloadFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.reason, style = Material3Small, color = XColors.Red)
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Downloading -> TextButton(
                    onClick = {},
                    enabled = false,
                ) { Text("下载中…", color = XColors.Text2) }
                is UpdateState.InstallBlocked ->
                    UpdatePrimaryButton("去开启", XColors.Blue) { onOpenInstallSettings() }
                is UpdateState.DownloadFailed ->
                    UpdatePrimaryButton("重试", XColors.Blue) { onDownload() }
                else ->
                    UpdatePrimaryButton("立即更新", XColors.Blue) { onDownload() }
            }
        },
        dismissButton = {
            when (state) {
                is UpdateState.Downloading ->
                    TextButton(onClick = onCancelDownload) {
                        Text("取消下载", color = XColors.Red)
                    }
                else ->
                    TextButton(onClick = onDismiss) { Text("稍后再说", color = XColors.Text2) }
            }
        },
    )
}

private val Material3Small = androidx.compose.ui.text.TextStyle(
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
)

@Composable
private fun UpdatePrimaryButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) { Text(text, fontWeight = FontWeight.Bold) }
}
