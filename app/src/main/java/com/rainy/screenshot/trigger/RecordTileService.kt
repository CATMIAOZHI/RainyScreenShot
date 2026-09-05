package com.rainy.screenshot.trigger

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rainy.screenshot.recordingSessionManager
import com.rainy.screenshot.settingsStore
import com.rainy.screenshot.session.RecordingSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 静默录屏快捷磁贴。
 *
 * 点一下：
 * - Idle → 开始录制（磁贴 ACTIVE）
 * - Recording → 停止（SIGINT 收尾，磁贴回 INACTIVE）
 * - 失败：磁贴短暂 UNAVAILABLE 灰显后复位（E5 修复：不再完全静默）
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
            var success = true
            when (manager.currentState()) {
                is RecordingSessionManager.SessionState.Recording -> {
                    val result = manager.stop()
                    success = result is RecordingSessionManager.StopResult.Success
                }
                else -> {
                    // 阶段 3：磁贴录屏同样使用设置页持久化参数
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
                    kotlinx.coroutines.delay(800)
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