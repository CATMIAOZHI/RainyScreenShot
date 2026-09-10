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
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.rainy.screenshot.util.LocaleCompat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel：状态桥 + 操作入口（阶段 3：操作使用持久化参数）。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val shellExecutor: com.rainy.screenshot.capture.ShellExecutor,
    private val screenshotEngine: com.rainy.screenshot.capture.ScreenshotEngine,
    private val recordingSessionManager: RecordingSessionManager,
    private val settingsStore: com.rainy.screenshot.data.local.SettingsStore,
    private val floatingBallController: com.rainy.screenshot.overlay.FloatingBallController
) : ViewModel() {

    val sessionState: StateFlow<RecordingSessionManager.SessionState> =
        recordingSessionManager.state

    /** 悬浮球开关状态（首页开关卡）。 */
    val floatingBallEnabled = floatingBallController.enabled

    /** 任务栏磁贴状态（截屏/录屏磁贴是否在任务栏中）。 */
    private val _tileStates = MutableStateFlow(TileStates(screenshot = false, record = false))
    val tileStates: StateFlow<TileStates> = _tileStates.asStateFlow()

    /** 任务栏磁贴状态对。 */
    data class TileStates(val screenshot: Boolean, val record: Boolean)

    /** 探测两个磁贴当前是否在任务栏中（Shizuku 就绪时）。 */
    fun refreshTileStates() {
        if (!shellExecutor.isEnvironmentReady()) return
        viewModelScope.launch {
            val ss = shellExecutor.hasQuickSettingsTile(
                com.rainy.screenshot.trigger.ScreenshotTileService::class.java
            )
            val rec = shellExecutor.hasQuickSettingsTile(
                com.rainy.screenshot.trigger.RecordTileService::class.java
            )
            _tileStates.value = TileStates(ss, rec)
        }
    }

    /** 手动把截屏磁贴加入任务栏（§13 实测命令，幂等）。 */
    fun addScreenshotTile(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = shellExecutor.addQuickSettingsTile(
                com.rainy.screenshot.trigger.ScreenshotTileService::class.java
            )
            refreshTileStates()
            onDone(ok)
        }
    }

    /** 手动把录屏磁贴加入任务栏。 */
    fun addRecordTile(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = shellExecutor.addQuickSettingsTile(
                com.rainy.screenshot.trigger.RecordTileService::class.java
            )
            refreshTileStates()
            onDone(ok)
        }
    }

    /** 从任务栏移除截屏磁贴。 */
    fun removeScreenshotTile(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = shellExecutor.removeQuickSettingsTile(
                com.rainy.screenshot.trigger.ScreenshotTileService::class.java
            )
            refreshTileStates()
            onDone(ok)
        }
    }

    /** 从任务栏移除录屏磁贴。 */
    fun removeRecordTile(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = shellExecutor.removeQuickSettingsTile(
                com.rainy.screenshot.trigger.RecordTileService::class.java
            )
            refreshTileStates()
            onDone(ok)
        }
    }

    /** 悬浮球开关切换（授权失败经 onNeedManualGrant 引导系统设置页）。 */
    fun toggleBall(on: Boolean, onNeedManualGrant: () -> Unit) {
        floatingBallController.toggle(on, onNeedManualGrant)
    }

    /** 环境就绪状态（真实探测，E2 修复） */
    private val _envReady = MutableStateFlow(true)
    val envReady: StateFlow<Boolean> = _envReady

    fun refreshEnv() {
        _envReady.value = shellExecutor.isEnvironmentReady()
        // 磁贴状态随环境状态一起刷新（进首页时探测；授权结果按项目
        // 既有约定「下次进入时反映」，不做生命周期强依赖）
        refreshTileStates()
    }

    /**
     * 延迟截屏（阶段 3-4）：等待 [delayMs] 后截一张。
     * （读持久化截屏参数。用户截屏主入口走磁贴/悬浮球，
     * 主页延时保留参数化入口；连拍入口已按需求移除）
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

    /** B7 修复：stop 结果结构化处理，失败不再误报成功。 */
    fun toggleRecord(onDone: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            val state = recordingSessionManager.currentState()
            if (state is RecordingSessionManager.SessionState.Recording) {
                when (val result = recordingSessionManager.stop()) {
                    is RecordingSessionManager.StopResult.Success ->
                        onDone(true, "STOP:${result.durationMs / 1000}")
                    is RecordingSessionManager.StopResult.Failure ->
                        onDone(false, friendlyErrorText(LocaleCompat.localized(appContext), result.reason))
                    RecordingSessionManager.StopResult.NotRecording ->
                        onDone(false, LocaleCompat.localized(appContext).getString(R.string.common_no_recording))
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

    private fun friendlyError(e: Exception): String =
        friendlyErrorText(LocaleCompat.localized(appContext), when (e) {
            is ShellException.NotInstalled ->
                if (e.backend == com.rainy.screenshot.capture.ServiceBackend.PORTER) "PORTER_NOT_INSTALLED"
                else "SHIZUKU_NOT_INSTALLED"
            is ShellException.NotRunning ->
                if (e.backend == com.rainy.screenshot.capture.ServiceBackend.PORTER) "PORTER_NOT_RUNNING"
                else "SHIZUKU_NOT_RUNNING"
            is ShellException.NotGranted ->
                if (e.backend == com.rainy.screenshot.capture.ServiceBackend.PORTER) "PORTER_NOT_GRANTED"
                else "SHIZUKU_NOT_GRANTED"
            else -> e.message ?: ""
        })
}

/** 引擎/管理器英文 reason → 本地化可读文案（单一出口，Toast 与状态卡共用）。 */
fun friendlyErrorText(context: android.content.Context, raw: String): String = when {
    raw == "PORTER_NOT_INSTALLED" -> context.getString(R.string.err_porter_not_installed)
    raw == "PORTER_NOT_RUNNING" -> context.getString(R.string.err_porter_not_running)
    raw == "PORTER_NOT_GRANTED" -> context.getString(R.string.err_porter_not_granted)
    raw == "SHIZUKU_NOT_INSTALLED" -> context.getString(R.string.err_shizuku_not_installed)
    raw == "SHIZUKU_NOT_RUNNING" -> context.getString(R.string.err_shizuku_not_running)
    raw == "SHIZUKU_NOT_GRANTED" -> context.getString(R.string.err_shizuku_not_granted)
    raw.contains("already active") -> context.getString(R.string.err_already_active)
    raw.contains("no active recording") -> context.getString(R.string.err_no_active_recording)
    raw.contains("timeout") || raw.contains("timed out", ignoreCase = true) -> context.getString(R.string.err_timeout)
    raw.contains("still alive") || raw.contains("retry stop") -> context.getString(R.string.err_still_alive)
    raw.contains("kill -INT failed") -> context.getString(R.string.err_kill_failed)
    raw.contains("pid locate") -> context.getString(R.string.err_pid_locate)
    raw.contains("exited unexpectedly") -> context.getString(R.string.err_exited_unexpectedly)
    raw.isBlank() -> context.getString(R.string.common_unknown_error)
    else -> raw.take(120)
}

/** 状态卡 Failed reason 渲染（N5 修复：与 Toast 同源映射）。 */
@Composable
private fun friendlyFailedReason(reason: String): String {
    val context = LocalContext.current
    return if (reason.startsWith("SHIZUKU") || reason.contains("failed") ||
        reason.contains("timed out") || reason.contains("active") ||
        reason.contains("alive") || reason.contains("unexpectedly")) {
        friendlyErrorText(context, reason)
    } else {
        reason.take(120)
    }
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
                        Icon(Icons.Filled.History, contentDescription = stringResource(R.string.home_nav_history))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_nav_settings))
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
                    icon = {
                        Icon(
                            if (sessionState is RecordingSessionManager.SessionState.Recording)
                                Icons.Filled.StopCircle else Icons.Filled.Videocam,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    label = stringResource(
                            if (sessionState is RecordingSessionManager.SessionState.Recording)
                                R.string.home_record_stop else R.string.home_record_start
                        ),
                    containerColor = if (sessionState is RecordingSessionManager.SessionState.Recording)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    onClick = {
                        viewModel.toggleRecord(onDone = { ok, msg ->
                            when {
                                ok && msg.startsWith("START") ->
                                    Toast.makeText(context, R.string.home_record_toast_start, Toast.LENGTH_SHORT).show()
                                ok && msg.startsWith("STOP") -> {
                                    val secs = msg.removePrefix("STOP:").toIntOrNull() ?: 0
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.home_record_toast_stop, secs),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                else -> Toast.makeText(
                                    context,
                                    context.getString(R.string.home_toast_failed, msg),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        })
                    }
                )
            }

            // ─── 悬浮球开关（首页直达，磁贴指路保留） ───
            BallSwitchCard(
                onOpenHistory = onOpenHistory,
                viewModel = viewModel
            )

            // ─── 任务栏磁贴（纯手动添加，两按钮） ───
            TileSwitchCard(viewModel = viewModel)

            // 延时截屏（参数化入口，设置页可调）
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
                    label = stringResource(R.string.home_delay_button),
                    containerColor = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        Toast.makeText(context, R.string.home_delay_toast_start, Toast.LENGTH_SHORT).show()
                        viewModel.screenshotDelayed(3_000L) { ok, msg ->
                            if (ok) {
                                Toast.makeText(context, R.string.home_delay_toast_done, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.home_delay_toast_failed, msg),
                                    Toast.LENGTH_LONG
                                ).show()
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

/**
 * 任务栏磁贴卡（首页直达，纯手动添加/移除）。
 *
 * - 截屏/录屏两行：当前状态 + 添加/移除按钮
 * - 不自动注入：用户明确点击才操作任务栏（水晴要求）
 * - 点击磁贴会自动收起面板再截/录，不会拍到面板
 */
@Composable
private fun TileSwitchCard(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val tileStates by viewModel.tileStates.collectAsState()
    val envReady by viewModel.envReady.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.tile_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.tile_card_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(12.dp))

            TileRow(
                title = stringResource(R.string.tile_row_screenshot),
                added = tileStates.screenshot,
                envReady = envReady,
                onAdd = {
                    viewModel.addScreenshotTile { ok ->
                        if (!ok) {
                            Toast.makeText(context, R.string.tile_add_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onRemove = {
                    viewModel.removeScreenshotTile { ok ->
                        if (!ok) {
                            Toast.makeText(context, R.string.tile_remove_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            TileRow(
                title = stringResource(R.string.tile_row_record),
                added = tileStates.record,
                envReady = envReady,
                onAdd = {
                    viewModel.addRecordTile { ok ->
                        if (!ok) {
                            Toast.makeText(context, R.string.tile_add_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onRemove = {
                    viewModel.removeRecordTile { ok ->
                        if (!ok) {
                            Toast.makeText(context, R.string.tile_remove_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
}

/** 单个任务栏磁贴行（标题 + 当前状态 + 添加/移除按钮）。 */
@Composable
private fun TileRow(
    title: String,
    added: Boolean,
    envReady: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                when {
                    !envReady -> stringResource(R.string.tile_state_env_not_ready)
                    added -> stringResource(R.string.tile_state_added)
                    else -> stringResource(R.string.tile_state_not_added)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        if (added) {
            TextButton(
                onClick = onRemove,
                enabled = envReady
            ) {
                Text(stringResource(R.string.tile_remove), color = MaterialTheme.colorScheme.error)
            }
        } else {
            TextButton(
                onClick = onAdd,
                enabled = envReady
            ) {
                Text(stringResource(R.string.tile_add))
            }
        }
    }
}

/**
 * 悬浮球开关卡（首页直达开关，磁贴指路 + 历史入口保留）。
 * 开关逻辑统一走 FloatingBallController（与设置页/冷启动同源）。
 */
@Composable
private fun BallSwitchCard(
    onOpenHistory: () -> Unit,
    viewModel: HomeViewModel
) {
    val context = LocalContext.current
    val enabled by viewModel.floatingBallEnabled.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ball_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(
                            if (enabled) R.string.ball_card_on else R.string.ball_card_off
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        viewModel.toggleBall(on) {
                            // Shizuku 授权失败 → 引导系统设置页
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ball_card_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ball_card_history))
                }
            }
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
                stringResource(R.string.env_guide_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.env_guide_body),
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
                    stringResource(R.string.status_recording),
                    stringResource(
                        R.string.status_recording_desc,
                        com.rainy.screenshot.capture.CaptureFileNamer.formatDuration(
                            System.currentTimeMillis() - state.startedAt
                        )
                    ),
                    StatusGreen
                )
                is RecordingSessionManager.SessionState.Completed -> Triple(
                    stringResource(R.string.status_completed),
                    stringResource(R.string.status_completed_desc, state.durationMs / 1000),
                    MaterialTheme.colorScheme.primary
                )
                is RecordingSessionManager.SessionState.Failed -> Triple(
                    stringResource(R.string.status_failed),
                    friendlyFailedReason(state.reason),
                    StatusRed
                )
                RecordingSessionManager.SessionState.Idle -> Triple(
                    stringResource(R.string.status_idle),
                    stringResource(R.string.status_idle_desc),
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
                stringResource(R.string.info_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.info_body),
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
