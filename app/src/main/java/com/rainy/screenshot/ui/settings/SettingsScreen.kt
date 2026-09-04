package com.rainy.screenshot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.rainy.screenshot.capture.ShellException
import com.rainy.screenshot.capture.ShellExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shizuku 环境状态（真实探测，E1 修复）。 */
data class EnvStatus(
    val installed: Boolean,
    val running: Boolean,
    val granted: Boolean
) {
    val ready: Boolean get() = installed && running && granted
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val shellExecutor: ShellExecutor
) : ViewModel() {

    private val _env = MutableStateFlow(
        EnvStatus(installed = false, running = false, granted = false)
    )
    val env: StateFlow<EnvStatus> = _env.asStateFlow()

    fun refresh() {
        _env.value = EnvStatus(
            installed = shellExecutor.isShizukuInstalled(),
            running = shellExecutor.isShizukuRunning(),
            granted = shellExecutor.isShizukuGranted()
        )
    }
}

/**
 * 设置页（v1：环境状态真实展示 + 参数说明）。
 * 阶段 3 扩展：录屏参数（码率/分辨率/时长）、悬浮球开关、输出目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val env by viewModel.env.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard("Shizuku 环境") {
                SettingInfo("已安装", if (env.installed) "是" else "否")
                Spacer(Modifier.height(8.dp))
                SettingInfo("服务运行", if (env.running) "运行中" else "未运行")
                Spacer(Modifier.height(8.dp))
                SettingInfo("本应用授权", if (env.granted) "已授予" else "未授予")
                Spacer(Modifier.height(8.dp))
                SettingInfo(
                    "整体状态",
                    if (env.ready) "就绪" else "未就绪（按上述项排查）"
                )
            }

            SectionCard("录屏参数（当前为默认值，阶段 3 开放自定义）") {
                SettingInfo("码率", "8 Mbps")
                Spacer(Modifier.height(8.dp))
                SettingInfo("时长上限", "180 秒")
                Spacer(Modifier.height(8.dp))
                SettingInfo("分辨率", "跟随屏幕")
            }

            SectionCard("关于") {
                SettingInfo("版本", "0.1.0")
                Spacer(Modifier.height(8.dp))
                SettingInfo("项目", "RainyScreenShot · the Rainy Family tools")
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingInfo(
    label: String,
    value: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}