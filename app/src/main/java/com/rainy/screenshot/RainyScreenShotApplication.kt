package com.rainy.screenshot

import android.app.Application
import android.content.Context
import com.rainy.screenshot.capture.ScreenshotEngine
import com.rainy.screenshot.session.RecordingSessionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** 截屏快捷入口（磁贴用）。 */
suspend fun Application.screenshotQuick(): Boolean {
    val engine = (this as RainyScreenShotApplication).screenshotEngine
    return runCatching { engine.capture(); true }.getOrElse { false }
}