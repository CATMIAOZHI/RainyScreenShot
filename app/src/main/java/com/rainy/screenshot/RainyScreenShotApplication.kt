package com.rainy.screenshot

import android.app.Application
import android.content.Context
import com.rainy.screenshot.capture.ScreenshotEngine
import com.rainy.screenshot.data.local.SettingsStore
import com.rainy.screenshot.session.RecordingSessionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * RainyScreenShot 入口 Application。
 *
 * 职责：
 * - @HiltAndroidApp 触发 Hilt 组件树
 * - 冷启动时恢复录制会话（APP 被杀后录屏进程仍在 shell uid 中运行）
 * - 对磁贴 / 悬浮球暴露稳定入口（TileService 无法 Hilt 注入）
 */
@HiltAndroidApp
class RainyScreenShotApplication : Application() {

    @Inject
    lateinit var recordingSessionManager: RecordingSessionManager

    @Inject
    lateinit var screenshotEngine: ScreenshotEngine

    @Inject
    lateinit var settingsStore: SettingsStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 恢复上次未收尾的录制会话（如有）
        appScope.launch { runCatching { recordingSessionManager.restore() } }
    }
}

/** 磁贴 / 悬浮球的便捷访问入口（TileService 无 Hilt 注入能力）。 */
val Application.recordingSessionManager: RecordingSessionManager
    get() = (this as RainyScreenShotApplication).recordingSessionManager

/** 设置持久化入口（磁贴 / 悬浮球用）。 */
val Application.settingsStore: SettingsStore
    get() = (this as RainyScreenShotApplication).settingsStore

/** 截屏快捷入口（磁贴/悬浮球用，阶段 3：读取持久化截屏参数）。 */
suspend fun Application.screenshotQuick(): Boolean {
    val app = this as RainyScreenShotApplication
    val config = app.settingsStore.screenshotConfigFlow.first()
    return runCatching { app.screenshotEngine.capture(config); true }.getOrElse { false }
}