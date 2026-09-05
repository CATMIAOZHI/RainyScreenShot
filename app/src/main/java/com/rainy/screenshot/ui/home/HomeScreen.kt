package com.rainy.screenshot.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rainy.screenshot.R
import com.rainy.screenshot.capture.ShellException
import com.rainy.screenshot.session.RecordingSessionManager
import com.rainy.screenshot.ui.theme.StatusGreen
import com.rainy.screenshot.ui.theme.StatusRed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel：状态桥 + 操作入口（阶段 3：操作使用持久化参数）。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val shellExecutor: com.rainy.screenshot.capture.ShellExecutor,
    private val screenshotEngine: com.rainy.screenshot.capture.ScreenshotEngine,
    private val recordingSessionManager: RecordingSessionManager,
    private val settingsStore: com.rainy.screenshot.data.local.SettingsStore
) : ViewModel() {

    val sessionState: StateFlow<RecordingSessionManager.SessionState> =
        recordingSessionManager.state

    /** 环境就绪状态（真实探测，E2 修复） */
    private val _envReady = MutableStateFlow(true)
    val envReady: StateFlow<Boolean> = _envReady

    fun refreshEnv() {
        _envReady.value = shellExecutor.isEnvironmentReady()
    }

    fun screenshot(onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                // 阶段 3：读取设置页持久化的截屏参数（格式 RAW/PNG、display-id）
                val config = settingsStore.screenshotConfigFlow.first()
                screenshotEngine.capture(config)
                onDone(true, "")
            } catch (e: Exception) {
                onDone(false, friendlyError(e))
            }
        }
    }

    /**
     * 延迟截屏（阶段 3-4）：等待 [delayMs] 后截一张。
     */
    fun screenshotDelayed(delayMs: Long, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                delay(delayMs)
                val config = settingsStore.screenshotConfigFlow.first()
                screenshotEngine.capture(config)
                onDone(true, "")
            } catch (e: Exception) {
                onDone(false, friendlyError(e))
            }
        }
    }

    /**
     * 连拍（阶段 3-4）：连续截 [count] 张，间隔 [intervalMs]。
     */
    fun screenshotSeries(
        count: Int,
        intervalMs: Long,
        onDone: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val config = settingsStore.screenshotConfigFlow.first()
                val files = screenshotEngine.captureSeries(config, count, intervalMs)
                onDone(true, "SERIES:${files.size}")
            } catch (e: Exception) {
                onDone(false, friendlyError(e))
            }
        }
    }

    /** B7 修复：stop 结果结构化处理，失败不再误报成功。 */
    fun toggleRecord(onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            val state = recordingSessionManager.currentState()
            if (state is RecordingSessionManager.SessionState.Recording) {
                when (val result = recordingSessionManager.stop()) {
                    is RecordingSessionManager.StopResult.Success ->
                        onDone(true, "STOP:${result.durationMs / 1000}")
                    is RecordingSessionManager.StopResult.Failure ->
                        onDone(false, friendlyErrorText(result.reason))
                    RecordingSessionManager.StopResult.NotRecording ->
                        onDone(false, "当前没有正在进行的录制")
                }
            } else {
                try {
                    // 阶段 3：读取设置页持久化的录屏参数（码率/分辨率/时长等）
                    val config = settingsStore.recordConfigFlow.first().normalized()
                    recordingSessionManager.start(config)
                    onDone(true, "START")
                } catch (e: Exception) {
                    onDone(false, friendlyError(e))
                }
            }
        }
    }

    private fun friendlyError(e: Exception): String = friendlyErrorText(
        when (e) {
            is ShellException.NotInstalled -> "SHIZUKU_NOT_INSTALLED"
            is ShellException.NotRunning -> "SHIZUKU_NOT_RUNNING"
            is ShellException.NotGranted -> "SHIZUKU_NOT_GRANTED"
            else -> e.message ?: "未知错误"
        }
    )
}

/** 引擎/管理器英文 reason → 中文可读文案（单一出口，Toast 与状态卡共用）。 */
fun friendlyErrorText(raw: String): String = when {
    raw == "SHIZUKU_NOT_INSTALLED" -> "Shizuku 未安装，请先安装并激活 Shizuku"
    raw == "SHIZUKU_NOT_RUNNING" -> "Shizuku 未运行，请到 Shizuku 应用启动服务"
    raw == "SHIZUKU_NOT_GRANTED" -> "未授予本应用 Shizuku 权限，请在 Shizuku 中授权"
    raw.contains("already active") -> "已在录制中，请先停止当前录制"
    raw.contains("no active recording") -> "当前没有正在进行的录制"
    raw.contains("timeout") || raw.contains("timed out", ignoreCase = true) -> "命令执行超时，请重试（Shizuku 环境可能不稳定）"
    raw.contains("still alive") || raw.contains("retry stop") -> "停止信号未生效，录制仍在进行，请重试停止"
    raw.contains("kill -INT failed") -> "停止信号发送失败，录制可能仍在进行，请重试"
    raw.contains("pid locate") -> "录屏进程启动异常（无法定位进程），已自动清理"
    raw.contains("exited unexpectedly") -> "录屏进程异常退出，文件可能不完整"
    raw.isBlank() -> "未知错误"
    else -> raw.take(120)
}

/** 状态卡 Failed reason 渲染（N5 修复：与 Toast 同源映射）。 */
@Composable
private fun friendlyFailedReason(reason: String): String =
    if (reason.startsWith("SHIZUKU") || reason.contains("failed") ||
        reason.contains("timed out") || reason.contains("active") ||
        reason.contains("alive") || reason.contains("unexpectedly")) {
        friendlyErrorText(reason)
    } else {
        reason.take(120)
    }

/**
 * 首页：状态卡 + 截屏/录屏主操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val sessionState by viewModel.sessionState.collectAsState()
    val envReady by viewModel.envReady.collectAsState()

    // 进入页面时刷新环境状态（E2：真实探测替代假设）
    LaunchedEffect(Unit) { viewModel.refreshEnv() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "历史")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
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
            // E2 修复：环境未就绪时展示引导卡
            if (!envReady) {
                EnvGuideCard()
            }

            // ─── 状态卡 ───
            StatusCard(sessionState)

            // ─── 主操作 ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CaptureButton(
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Filled.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(28.dp)) },
                    label = "静默截屏",
                    containerColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        Toast.makeText(context, "正在截屏…", Toast.LENGTH_SHORT).show()
                        viewModel.screenshot(onDone = { ok, msg ->
                            if (ok) {
                                Toast.makeText(context, "截屏完成，已存入私有目录", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "截屏失败：$msg", Toast.LENGTH_LONG).show()
                            }
                        })
                    }
                )
                CaptureButton(
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(
                            if (sessionState is RecordingSessionManager.SessionState.Recording)
                                Icons.Filled.StopCircle else Icons.Filled.Videocam,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    label = if (sessionState is RecordingSessionManager.SessionState.Recording) "停止录制"
                            else "静默录屏",
                    containerColor = if (sessionState is RecordingSessionManager.SessionState.Recording)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    onClick = {
                        viewModel.toggleRecord(onDone = { ok, msg ->
                            when {
                                ok && msg.startsWith("START") ->
                                    Toast.makeText(context, "录制已开始（无感知）", Toast.LENGTH_SHORT).show()
                                ok && msg.startsWith("STOP") -> {
                                    val secs = msg.removePrefix("STOP:")
                                    Toast.makeText(context, "录制已收尾保存（时长 $secs 秒）", Toast.LENGTH_SHORT).show()
                                }
                                else -> Toast.makeText(context, "操作失败：$msg", Toast.LENGTH_LONG).show()
                            }
                        })
                    }
                )
            }

            // 提示卡
            // 阶段 3-4：延迟截屏 / 连拍快捷入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CaptureButton(
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(
                            Icons.Filled.Timer,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = "延时 3s 截屏",
                    containerColor = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        Toast.makeText(context, "3 秒后截屏", Toast.LENGTH_SHORT).show()
                        viewModel.screenshotDelayed(3_000L) { ok, msg ->
                            if (ok) {
                                Toast.makeText(context, "延迟截屏完成", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "延迟截屏失败：$msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
                CaptureButton(
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(
                            Icons.Filled.BurstMode,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = "连拍 3 张",
                    containerColor = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        Toast.makeText(context, "连拍 3 张，间隔 1 秒", Toast.LENGTH_SHORT).show()
                        viewModel.screenshotSeries(3, 1_000L) { ok, msg ->
                            if (ok) {
                                val n = msg.removePrefix("SERIES:")
                                Toast.makeText(context, "连拍完成，成功 $n 张", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "连拍失败：$msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }

            // 提示卡
            InfoCard()
        }
    }
}

/** E2 修复：环境未就绪引导卡（替代静默失败）。 */
@Composable
private fun EnvGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Shizuku 环境未就绪",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "静默截屏/录屏依赖 Shizuku shell 权限。请：\n" +
                "1. 安装 Shizuku（moe.shizuku.privileged.api）\n" +
                "2. 在 Shizuku 中通过 ADB 或 Root 启动服务\n" +
                "3. 在 Shizuku 授权列表中允许「雨晴截屏」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun StatusCard(state: RecordingSessionManager.SessionState) {
    // N4 修复：录制中时长需要实时跳动 → 每秒触发重组的 ticker
    var ticker by remember { mutableIntStateOf(0) }
    if (state is RecordingSessionManager.SessionState.Recording) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                ticker++
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") val tick = ticker // 触发重组

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            val (title, desc, color) = when (state) {
                is RecordingSessionManager.SessionState.Recording -> Triple(
                    "录制中",
                    "目标 App 无感知 · 无状态栏指示 · ${com.rainy.screenshot.capture.CaptureFileNamer.formatDuration(System.currentTimeMillis() - state.startedAt)}",
                    StatusGreen
                )
                is RecordingSessionManager.SessionState.Completed -> Triple(
                    "已完成",
                    "已录制 ${state.durationMs / 1000} 秒 · 文件在私有目录",
                    MaterialTheme.colorScheme.primary
                )
                is RecordingSessionManager.SessionState.Failed -> Triple(
                    "上次操作失败",
                    friendlyFailedReason(state.reason),
                    StatusRed
                )
                RecordingSessionManager.SessionState.Idle -> Triple(
                    "就绪",
                    "Shizuku shell 通道待命",
                    MaterialTheme.colorScheme.primary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Videocam,
                    null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "使用说明",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "• 截屏/录屏经 Shizuku shell 执行，不经 MediaProjection\n" +
                "• 无系统弹窗、无状态栏投屏图标、目标 App 无回调\n" +
                "• 快捷磁贴可在任意界面下拉触发（安全锁屏下除外）\n" +  // E3 修正
                "• 输出保存在 APP 私有目录，历史页可查看/删除/分享",        // B4 修正
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CaptureButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon()
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}