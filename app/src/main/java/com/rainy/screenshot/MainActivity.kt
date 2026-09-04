package com.rainy.screenshot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rainy.screenshot.ui.RainyScreenShotNavHost
import com.rainy.screenshot.ui.components.RainyBackground
import com.rainy.screenshot.ui.theme.RainyScreenShotTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * APP 入口。
 *
 * 布局策略（对齐 RainyToken）：
 *  - 外层 RainyScreenShotTheme（品牌色）→ RainyBackground（樱粉渐变）→ NavHost
 *  - 每个页面自己处理 Scaffold 与 padding，避免双 Scaffold 嵌套
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RainyScreenShotTheme {
                RainyBackground {
                    RainyScreenShotNavHost()
                }
            }
        }
    }
}