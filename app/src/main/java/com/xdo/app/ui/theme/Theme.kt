package com.xdo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1DA1F2),
    onPrimary = Color.White,
    secondary = Color(0xFF0F6E56),
    tertiary = Color(0xFF993C1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF85C6F2),
    onPrimary = Color(0xFF00324D),
    secondary = Color(0xFF5DCAA5),
    tertiary = Color(0xFFF0997B),
)

@Composable
fun XDoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}