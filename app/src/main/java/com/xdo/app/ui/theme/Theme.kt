package com.xdo.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 全局配色：对齐 X「Lights out」深色风格（单主题，不跟随系统）。
 * 所有界面直接引用 XColors 取色，MaterialTheme.colorScheme 同步映射，
 * 供 Dialog/Switch 等系统组件自动继承深色外观。
 */
object XColors {
    val Blue = Color(0xFF1D9BF0) // 主操作/选中/进度/链接
    val BlueDim = Color(0x1F1D9BF0) // 蓝色 12% 底（选中底色）
    val Green = Color(0xFF00BA7C) // 成功/已完成
    val Red = Color(0xFFF4212E) // 错误/破坏性
    val RedDim = Color(0x26F4212E) // 滑动删除底色
    val Yellow = Color(0xFFFFD400) // 暂停/警告
    val Bg = Color(0xFF000000) // 页面背景
    val Surface = Color(0xFF16181C) // 卡片/输入框/弹窗
    val Surface2 = Color(0xFF202327) // Snackbar/菜单/悬浮层
    val Border = Color(0xFF2F3336) // 描边/分隔线
    val Text = Color(0xFFE7E9EA) // 主文本
    val Text2 = Color(0xFF71767B) // 次文本/占位
}

private val DarkColors = darkColorScheme(
    primary = XColors.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF12324A),
    onPrimaryContainer = Color(0xFFA8D5F5),
    secondary = XColors.Blue,
    onSecondary = Color.White,
    tertiary = XColors.Yellow,
    onTertiary = Color.Black,
    background = XColors.Bg,
    onBackground = XColors.Text,
    surface = XColors.Surface,
    onSurface = XColors.Text,
    surfaceVariant = XColors.Surface2,
    onSurfaceVariant = XColors.Text2,
    surfaceTint = Color.Transparent,
    outline = XColors.Text2,
    outlineVariant = XColors.Border,
    error = XColors.Red,
    onError = Color.White,
    errorContainer = Color(0xFF2A1214),
    onErrorContainer = Color(0xFFFF8189),
    inverseSurface = XColors.Surface2,
    inverseOnSurface = XColors.Text,
)

@Composable
fun XDoTheme(
    // 单一深色主题：参数保留兼容旧调用点，但不再生效
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )

    // 深色背景 → 状态栏/导航栏图标保持亮色（单主题下固定）
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
}
