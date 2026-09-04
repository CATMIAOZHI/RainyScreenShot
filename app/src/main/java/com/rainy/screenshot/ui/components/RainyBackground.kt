package com.rainy.screenshot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.rainy.screenshot.ui.theme.CherryPinkDeep
import com.rainy.screenshot.ui.theme.CherryPinkLight
import com.rainy.screenshot.ui.theme.DarkBackground
import com.rainy.screenshot.ui.theme.DarkSurface

/**
 * 雨晴风格全局背景 —— 对齐 RainyToken。
 *
 * Light：樱粉渐变（#FFF0F5 → #FFD1DC）
 * Dark：暖深渐变（#1F1419 → #2A1F25）
 */
@Composable
fun RainyBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val brush = if (dark) {
        Brush.verticalGradient(
            colors = listOf(DarkBackground, DarkSurface)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(CherryPinkLight, CherryPinkDeep)
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush)
    ) {
        content()
    }
}