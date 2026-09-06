package com.rainy.screenshot.ui.settings

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.screenshot.capture.RecordConfig
import com.rainy.screenshot.capture.ScreenshotConfig
import com.rainy.screenshot.capture.ScreenshotFormat
import com.rainy.screenshot.capture.ShellExecutor
import com.rainy.screenshot.data.local.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val shellExecutor: ShellExecutor,
    private val settingsStore: SettingsStore,
    private val floatingBallController: com.rainy.screenshot.overlay.FloatingBallController
) : ViewModel() {

    private val _env = MutableStateFlow(
        EnvStatus(installed = false, running = false, granted = false)
    )
    val env: StateFlow<EnvStatus> = _env.asStateFlow()

    /** 录屏参数（DataStore 持久化） */
    val recordConfig: StateFlow<RecordConfig> = settingsStore.recordConfigFlow.stateIn(
        viewModelScope, SharingStarted.Eagerly, RecordConfig.DEFAULT
    )

    /** 截屏参数（DataStore 持久化） */
    val screenshotConfig: StateFlow<ScreenshotConfig> =
        settingsStore.screenshotConfigFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, ScreenshotConfig.DEFAULT
        )

    /** 悬浮球开关（SharedPreferences 持久化，B1 修复） */
    val floatingBallEnabled: StateFlow<Boolean> =
        settingsStore.floatingBallEnabledFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, false
        )

    fun refresh() {
        _env.value = EnvStatus(
            installed = shellExecutor.isShizukuInstalled(),
            running = shellExecutor.isShizukuRunning(),
            granted = shellExecutor.isShizukuGranted()
        )
    }

    fun setBitrate(mbps: Int) = launchEdit {
        settingsStore.setRecordConfig(recordConfig.value.copy(bitRateMbps = mbps))
    }

    fun setSize(size: String) = launchEdit {
        // 格式校验（审计卫生级 10）：仅允许「宽x高」或空串，防非法输入
        // 经 toArgs 裸拼进 sh -c 造成命令注入面；非法值直接忽略不写入
        val cleaned = size.trim()
        if (cleaned.isNotEmpty() && !Regex("^\\d+x\\d+$").matches(cleaned)) {
            return@launchEdit
        }
        settingsStore.setRecordConfig(
            recordConfig.value.copy(size = cleaned.takeIf { it.isNotBlank() })
        )
    }

    fun setTimeLimit(sec: Int) = launchEdit {
        settingsStore.setRecordConfig(recordConfig.value.copy(timeLimitSec = sec))
    }

    fun setRecordDisplayId(id: String) = launchEdit {
        settingsStore.setRecordConfig(
            recordConfig.value.copy(displayId = id.toLongOrNull())
        )
    }

    fun setBugreport(enabled: Boolean) = launchEdit {
        settingsStore.setRecordConfig(recordConfig.value.copy(bugreport = enabled))
    }

    fun setScreenshotFormat(format: ScreenshotFormat) = launchEdit {
        settingsStore.setScreenshotConfig(screenshotConfig.value.copy(format = format))
    }

    /** 悬浮球开关切换（统一走 FloatingBallController，与首页/冷启动同源）。 */
    fun toggleBall(on: Boolean, onNeedManualGrant: () -> Unit) {
        floatingBallController.toggle(on, onNeedManualGrant)
    }

    fun setScreenshotDisplayId(id: String) = launchEdit {
        settingsStore.setScreenshotConfig(
            screenshotConfig.value.copy(displayId = id.toLongOrNull())
        )
    }

    fun resetAll() = viewModelScope.launch {
        settingsStore.resetAll()
    }

    private fun launchEdit(block: suspend () -> Unit) =
        viewModelScope.launch { block() }
}

// ─── 设置页 UI ───

/** 码率档位文案（Mbps） */
private val BITRATE_LABELS = listOf("1", "4", "8", "12", "16", "24", "32")

/** 时长档位：秒 → 文案（0 = 「不限」档：normalized() 层封顶 1 小时自动收尾） */
private val TIME_LIMIT_LABELS = listOf("不限", "15s", "30s", "1m", "3m", "5m", "10m")
private val TIME_LIMIT_VALUES = listOf(0, 15, 30, 60, 180, 300, 600)

/**
 * 设置页（阶段 3：录屏/截屏参数自定义 + 环境状态 + 恢复默认）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val env by viewModel.env.collectAsState()
    val recordConfig by viewModel.recordConfig.collectAsState()
    val screenshotConfig by viewModel.screenshotConfig.collectAsState()
    val floatingBallEnabled by viewModel.floatingBallEnabled.collectAsState()
    // B-E6 修复：resetAll 不可逆操作加确认对话框（此前点一下即静默清空
    // 全部自定义参数）
    var showResetConfirm by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val context = LocalContext.current

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
                actions = {
                    IconButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "恢复默认")
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

            SectionCard("录屏参数") {
                ChoiceChips(
                    label = "码率",
                    options = BITRATE_LABELS,
                    selectedValue = recordConfig.bitRateMbps?.toString() ?: "8",
                    onSelect = { v -> viewModel.setBitrate(v.toInt()) }
                )
                Spacer(Modifier.height(12.dp))

                // B-E5 修复：本地输入态与 DataStore 回流解耦（此前 remember(recordConfig.size)
                // 每敲一键写 DataStore→flow 回流→键变→整个输入态重建 → 光标跳回起始、快输丢字）
                var sizeInput by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(recordConfig.size ?: "")
                }
                // 外部变更（resetAll/其他页面）同步进输入框
                androidx.compose.runtime.LaunchedEffect(recordConfig.size) {
                    if ((recordConfig.size ?: "") != sizeInput) {
                        sizeInput = recordConfig.size ?: ""
                    }
                }
                OutlinedTextField(
                    value = sizeInput,
                    onValueChange = { new ->
                        sizeInput = new
                        viewModel.setSize(new)
                    },
                    label = { Text("分辨率（留空=跟随屏幕）") },
                    placeholder = { Text("例如 1280x720 或 1920x1080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                ChoiceChips(
                    label = "时长上限",
                    options = TIME_LIMIT_LABELS,
                    selectedValue = when (recordConfig.timeLimitSec) {
                        0 -> "不限"
                        15 -> "15s"
                        30 -> "30s"
                        60 -> "1m"
                        180 -> "3m"
                        300 -> "5m"
                        600 -> "10m"
                        else -> "${recordConfig.timeLimitSec}s"
                    },
                    onSelect = { v ->
                        val sec = TIME_LIMIT_VALUES[
                            TIME_LIMIT_LABELS.indexOf(v).coerceAtLeast(0)
                        ]
                        viewModel.setTimeLimit(sec)
                    }
                )
                // 「不限」= 最长 1 小时自动收尾：App 被杀后 shell 侧
                // screenrecord 独立存活，时长上限是唯一进程级刹车
                Text(
                    "「不限」= 最长 1 小时自动收尾",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                // B-E5 修复：本地输入态与 DataStore 回流解耦（同上）
                var recordDisplayId by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(recordConfig.displayId?.toString() ?: "")
                }
                androidx.compose.runtime.LaunchedEffect(recordConfig.displayId) {
                    if ((recordConfig.displayId?.toString() ?: "") != recordDisplayId) {
                        recordDisplayId = recordConfig.displayId?.toString() ?: ""
                    }
                }
                OutlinedTextField(
                    value = recordDisplayId,
                    onValueChange = { new ->
                        recordDisplayId = new
                        viewModel.setRecordDisplayId(new)
                    },
                    label = { Text("display-id（留空=主屏）") },
                    placeholder = { Text("dumpsys SurfaceFlinger --display-id 查询") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "bugreport 叠加",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "录制画面叠加时间戳等系统信息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = recordConfig.bugreport,
                        onCheckedChange = { viewModel.setBugreport(it) }
                    )
                }
            }

            SectionCard("截屏参数") {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "输出格式",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "PNG 文件小、易查看；RAW 为 16 字节头+裸像素，文件巨大",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        ScreenshotFormat.entries.forEach { format ->
                            Row(
                                modifier = Modifier
                                    .selectable(
                                        selected = screenshotConfig.format == format,
                                        onClick = { viewModel.setScreenshotFormat(format) }
                                    )
                                    .padding(vertical = 6.dp)
                            ) {
                                RadioButton(
                                    selected = screenshotConfig.format == format,
                                    onClick = { viewModel.setScreenshotFormat(format) }
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    format.name,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // B-E5 修复：本地输入态与 DataStore 回流解耦（同上）
                var screenshotDisplayId by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(screenshotConfig.displayId?.toString() ?: "")
                }
                androidx.compose.runtime.LaunchedEffect(screenshotConfig.displayId) {
                    if ((screenshotConfig.displayId?.toString() ?: "") != screenshotDisplayId) {
                        screenshotDisplayId = screenshotConfig.displayId?.toString() ?: ""
                    }
                }
                OutlinedTextField(
                    value = screenshotDisplayId,
                    onValueChange = { new ->
                        screenshotDisplayId = new
                        viewModel.setScreenshotDisplayId(new)
                    },
                    label = { Text("display-id（留空=主屏）") },
                    placeholder = { Text("dumpsys SurfaceFlinger --display-id 查询") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionCard("悬浮球") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "常驻悬浮控制球",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "任意界面点击开始/停止录屏，可拖动 · 权限优先经 Shizuku 静默获取，失败时才跳系统设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = floatingBallEnabled,
                        onCheckedChange = { enabled ->
                            // 统一走 FloatingBallController（与首页开关/冷启动恢复同源）：
                            // 权限未到先 Shizuku 静默授权，失败才引导系统设置页
                            viewModel.toggleBall(enabled) {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse(
                                            "package:${context.packageName}"
                                        )
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )
                }
            }

            SectionCard("关于") {
                SettingInfo("版本", "0.1.0")
                Spacer(Modifier.height(8.dp))
                SettingInfo("项目", "RainyScreenShot · the Rainy Family tools")
            }
        }
    }

    // B-E6：resetAll 确认对话框（不可逆操作需二次确认）
    if (showResetConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认设置？") },
            text = { Text("将清除全部自定义参数（录屏/截屏参数）。悬浮球开关不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAll()
                    showResetConfirm = false
                }) {
                    Text("恢复默认", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/** 一行选项选择（码率/时长档位）。 */
@Composable
private fun ChoiceChips(
    label: String,
    options: List<String>,
    selectedValue: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        // B3 修复：窄屏（≤360dp）下 7 个 chip 可能溢出，加横向滚动兜底
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            options.forEach { text ->
                FilterChip(
                    selected = text == selectedValue,
                    onClick = { onSelect(text) },
                    label = { Text(text, style = MaterialTheme.typography.bodySmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                Spacer(Modifier.width(6.dp))
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