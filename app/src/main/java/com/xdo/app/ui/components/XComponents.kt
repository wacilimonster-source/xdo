package com.xdo.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xdo.app.data.DownloadRecord
import com.xdo.app.ui.theme.XColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 共享 UI 组件库：X 风格徽章/胶囊按钮/筛选 chip/Snackbar/格式化工具
// （设计基准：design/ui-prototype.html · design/UI优化说明.md）
// ============================================================

// ---- 徽章 ----

/** 清晰度徽章（如 720p）：蓝底蓝字小圆角 */
@Composable
fun QChip(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = XColors.Blue,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(XColors.BlueDim)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 格式徽章（HLS / MP4）：描边灰字 */
@Composable
fun FmtBadge(label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, XColors.Border),
        modifier = modifier,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = XColors.Text2,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** 「默认」徽章：蓝色描边 */
@Composable
fun DefBadge(modifier: Modifier = Modifier) {
    Text(
        "默认",
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = XColors.Blue,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(XColors.BlueDim)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

// ---- 胶囊按钮 ----

/**
 * 行内小胶囊按钮（28dp 高）。
 * filled=true：蓝底白字；否则描边幽灵样式。danger=true 时红色。
 * onClick 刻意放最后，调用点可用尾随 lambda。
 */
@Composable
fun MiniPill(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        filled && danger -> XColors.Red
        filled -> XColors.Blue
        else -> Color.Transparent
    }
    val fg = when {
        filled -> Color.White
        danger -> XColors.Red
        else -> XColors.Blue
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        border = if (filled) null else BorderStroke(1.dp, if (danger) XColors.Red else XColors.Blue),
        modifier = modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Text(
            text,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp),
        )
    }
}

// ---- 筛选 chip ----

/** 状态筛选 chip（选中=蓝底白字，未选中=描边灰字），带计数 */
@Composable
fun XFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) XColors.Blue else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, XColors.Border),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Color.White else XColors.Text2,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                count.toString(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else XColors.Text2,
            )
        }
    }
}

// ---- 小 spinner ----

/** 14dp 迷你加载圈（卡片状态行用） */
@Composable
fun MiniSpinner(modifier: Modifier = Modifier, size: Int = 14) {
    CircularProgressIndicator(
        modifier = modifier.size(size.dp),
        strokeWidth = 2.dp,
        color = XColors.Blue,
        trackColor = XColors.Border,
    )
}

// ---- Snackbar（统一深灰胶囊样式，动作蓝色） ----

@Composable
fun XSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = XColors.Surface2,
            contentColor = XColors.Text,
            actionColor = XColors.Blue,
            dismissActionContentColor = XColors.Text2,
            shape = RoundedCornerShape(12.dp),
        )
    }
}

// ---- 格式化工具 ----

/** 字节数 → 可读大小（1.4KB / 14.3MB / 1.2GB） */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1fGB".format(bytes / 1073741824.0)
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

/** 下载速度（B/s → x MB/s） */
fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024 * 1024 -> "%.1fMB/s".format(bytesPerSec / 1048576.0)
    bytesPerSec >= 1024 -> "%.0fKB/s".format(bytesPerSec / 1024.0)
    else -> "${bytesPerSec}B/s"
}

/** 剩余秒数 → 「剩余 8s / 剩余 1分12秒」 */
fun formatEta(sec: Long): String = when {
    sec >= 3600 -> "剩余 ${sec / 3600}小时${(sec % 3600) / 60}分"
    sec >= 60 -> "剩余 ${sec / 60}分${sec % 60}秒"
    else -> "剩余 ${sec}s"
}

/** 毫秒时长 → 0:58 / 1:03:42 */
fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 记录时间标签：「今天 14:32」「昨天 22:41」，更早显示「MM-dd HH:mm」。
 */
fun timeLabel(ts: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ts
    val now = java.util.Calendar.getInstance()
    return when {
        cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        -> "今天 ${fmt.format(Date(ts))}"
        cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) - cal.get(java.util.Calendar.DAY_OF_YEAR) == 1
        -> "昨天 ${fmt.format(Date(ts))}"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

/** 标题：取帖子正文中不含作者名/别名的首个非空行（去除品牌水印与装饰行），40 字截断 */
fun recordTitle(record: DownloadRecord): String {
    val lines = record.text
        .replace("\r", "")
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotBlank() }
    val content = lines.firstOrNull { line ->
        (record.authorName.isBlank() || !line.contains(record.authorName)) &&
            (record.handle.isBlank() || !line.contains(record.handle))
    } ?: lines.firstOrNull()
    return (content ?: "X 视频").take(40)
}

/** 区块标题（设置页分组用） */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = XColors.Text2,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

/** 设置行副标题 */
@Composable
fun SubText(text: String, color: Color = XColors.Text2, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = color,
        modifier = modifier.fillMaxWidth(),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 居中图标圆底（空状态插画用）：Surface 底 + 描边 */
@Composable
fun IconCircle(icon: ImageVector, size: Int = 76, iconSize: Int = 32, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = XColors.Surface,
        border = BorderStroke(1.dp, XColors.Border),
        modifier = modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = XColors.Text2, modifier = Modifier.size(iconSize.dp))
        }
    }
}

/** 卡片容器（Surface 封装：圆角 16 + Surface 底 + 描边） */
@Composable
fun XCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Color(0x142F3336)),
        modifier = if (onClick != null)
            modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
        else modifier,
    ) {
        content()
    }
}
