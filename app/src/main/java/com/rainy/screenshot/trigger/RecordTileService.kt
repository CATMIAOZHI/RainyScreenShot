package com.rainy.screenshot.trigger

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rainy.screenshot.collapseQuickSettings
import com.rainy.screenshot.recordingSessionManager
import com.rainy.screenshot.settingsStore
import com.rainy.screenshot.session.RecordingSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 静默录屏快捷磁贴。
 *
 * 点一下：
 * - Idle → 开始录制（磁贴 ACTIVE）
 * - Recording → 停止（SIGINT 收尾，磁贴回 INACTIVE）
 * - 失败：磁贴短暂 UNAVAILABLE 灰显后复位（E5 修复：不再完全静默）
 *
 * 面板处理（§13 实测）：磁贴从面板点击时面板必然展开——开始前收起
 * 面板 + 停顿（防第一帧录到面板）；停止前也收起（防收尾几帧录到
 * 面板）。collapse 幂等，失败忽略不阻断主链路。
 *
 * 状态快照（审计体验 1 修复）：点击时先快照会话状态，停顿后状态若
 * 已从 Recording 迁出（time-limit 恰好自动收尾），按「用户意图 = 停止
 * 且系统已停止」处理——刷新磁贴后直接返回，不误开新录制。
 */
class RecordTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val manager = application.recordingSessionManager

        scope.launch {
            // 点击瞬间快照（停顿窗口内 time-limit 收尾时用于意图判定）
            val stateBefore = manager.currentState()

            // 先收起面板（开始/停止均需要，防面板入镜）
            application.collapseQuickSettings()
            delay(ScreenshotTileService.COLLAPSE_SETTLE_MS)

            var success = true
            when (manager.currentState()) {
                is RecordingSessionManager.SessionState.Recording -> {
                    val result = manager.stop()
                    success = result is RecordingSessionManager.StopResult.Success
                }
                else -> {
                    if (stateBefore is RecordingSessionManager.SessionState.Recording) {
                        // 用户意图是停止，但录制已在停顿窗口内被 time-limit
                        // 自动收尾——不误开新录制，按真实状态刷新即可
                        updateTile()
                        return@launch
                    }
                    // 点击时即空闲 → 用户意图是开始录制（阶段 3：磁贴
                    // 录屏同样使用设置页持久化参数）
                    val config = application.settingsStore
                        .recordConfigFlow.first().normalized()
                    val outcome = runCatching { manager.start(config) }
                    success = outcome.isSuccess
                }
            }
            updateTile()

            // E5 修复：失败时短暂灰显反馈，用户能区分「已开始/失败」
            if (!success) {
                qsTile?.let { tile ->
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.updateTile()
                    delay(800)
                    // 失败后按真实状态复位
                    updateTile()
                }
            }
        }
    }

    private fun updateTile() {
        val manager = application.recordingSessionManager
        qsTile?.let { tile ->
            tile.state = when (manager.currentState()) {
                is RecordingSessionManager.SessionState.Recording -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        // B11 修复：释放协程
        scope.cancel()
        super.onDestroy()
    }
}