package com.rainy.screenshot.trigger

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rainy.screenshot.collapseQuickSettings
import com.rainy.screenshot.screenshotQuick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 静默截屏快捷磁贴。
 *
 * 点一下 = 执行一次 screencap（无需打开 APP）。
 * 磁贴从面板点击时面板必然展开——先收起面板再等收起动画完成，
 * 否则面板本身会被拍进截图（§13 实测修复）。
 *
 * 结果反馈（保持静默原则，无弹窗无通知）：
 * - 成功：磁贴短暂高亮后回 INACTIVE
 * - 失败：磁贴 UNAVAILABLE 短暂灰显后复位（E6 修复：不留悬置态）
 */
class ScreenshotTileService : TileService() {

    companion object {
        /**
         * 收起面板后的停顿（面板收起动画 + 布局刷新余量）。
         * §13 实测：collapse 下发后约 300ms 面板离屏；取 500ms 保守值，
         * 兼顾 MIUI 等厂商动画偏慢场景。
         */
        const val COLLAPSE_SETTLE_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
        }

        val app = application
        scope.launch {
            // 先收起面板（幂等命令，失败忽略不阻断截屏），等待离屏再截
            app.collapseQuickSettings()
            delay(COLLAPSE_SETTLE_MS)
            // screenshotQuick 内部已全路径容错返回 Boolean（含参数读取）
            val ok = app.screenshotQuick()
            qsTile?.let { tile ->
                tile.state = if (ok) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
                tile.updateTile()
                // 成功/失败都短暂反馈后复位（E6）
                delay(800)
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }

    override fun onDestroy() {
        // B11 修复：释放协程，防止 TileService 重建累积泄漏
        scope.cancel()
        super.onDestroy()
    }
}