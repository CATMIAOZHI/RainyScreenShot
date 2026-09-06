package com.rainy.screenshot

import android.app.Application
import com.rainy.screenshot.capture.ScreenshotEngine
import com.rainy.screenshot.capture.ShellExecutor
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
 * - Shizuku 授权就绪时一次性把截屏/录屏磁贴注入任务栏（§13 实测链路）
 */
@HiltAndroidApp
class RainyScreenShotApplication : Application() {

    @Inject
    lateinit var recordingSessionManager: RecordingSessionManager

    @Inject
    lateinit var screenshotEngine: ScreenshotEngine

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var shellExecutor: ShellExecutor

    @Inject
    lateinit var floatingBallController: com.rainy.screenshot.overlay.FloatingBallController

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 恢复上次未收尾的录制会话（如有）
        appScope.launch { runCatching { recordingSessionManager.restore() } }
        // 悬浮球自动恢复：开关开着就拉起服务（不必再进设置页手动开）。
        // 权限优先经 Shizuku 静默授予（§12 实测：appops set 可绕系统设置页），
        // 授予有失败/延迟也不阻断——服务侧 addView 重试机制兜底（见
        // FloatingBallService.startAddViewRetry）。开关状态存于
        // SharedPreferences，进程死亡不丢（B1 修复，见 SettingsStore.kt）
        floatingBallController.restoreOnBoot()
        // 磁贴自动注入：授权已就绪 + 未注入过 → 一次性注入（幂等命令，
        // 失败静默；标记语义见 injectQuickSettingsTilesOnce）
        injectQuickSettingsTilesOnce()
    }

    /**
     * 首次授权后把截屏/录屏磁贴注入任务栏（一次性，§13 实测链路）。
     *
     * 语义：本次安装生命周期内只自动注入一次（SharedPreferences 标记）——
     * 用户手动移除后不自动加回（尊重用户布局，防「删不掉的磁贴」）。
     * 重装/清数据后重新注入。需 Shizuku 已授权；全路径容错，任何失败
     * 静默跳过（设置页保留手动「添加到任务栏」入口兜底）。
     *
     * 公开：MainActivity 授权成功回调亦调用（冷启动时未授权 → 授权
     * 后立即补注入，不等下次启动）。
     */
    fun injectQuickSettingsTilesOnce() {
        if (settingsStore.areQuickSettingsTilesInjected()) return
        if (!shellExecutor.isEnvironmentReady()) return
        appScope.launch {
            val okScreenshot = shellExecutor.addQuickSettingsTile(
                com.rainy.screenshot.trigger.ScreenshotTileService::class.java
            )
            val okRecord = shellExecutor.addQuickSettingsTile(
                com.rainy.screenshot.trigger.RecordTileService::class.java
            )
            if (okScreenshot || okRecord) {
                settingsStore.setQuickSettingsTilesInjected(true)
            }
        }
    }
}

/** 磁贴 / 悬浮球的便捷访问入口（TileService 无 Hilt 注入能力）。
 *  注意：getter 解析依赖「成员属性优先于扩展」规则命中类内注入成员
 *  （成员改名/删除时此处会自递归栈溢出——扩展遮蔽既有模式，沿袭）。 */
val Application.recordingSessionManager: RecordingSessionManager
    get() = (this as RainyScreenShotApplication).recordingSessionManager

/** 设置持久化入口（磁贴 / 悬浮球用）。
 *  注意：同上，getter 依赖「成员属性优先于扩展」命中注入成员。 */
val Application.settingsStore: SettingsStore
    get() = (this as RainyScreenShotApplication).settingsStore

/** 截屏快捷入口（磁贴/悬浮球用，阶段 3：读取持久化截屏参数）。 */
suspend fun Application.screenshotQuick(): Boolean {
    val app = this as RainyScreenShotApplication
    val config = app.settingsStore.screenshotConfigFlow.first()
    return runCatching { app.screenshotEngine.capture(config); true }.getOrElse { false }
}

/** Shizuku shell 执行器入口（磁贴/悬浮球用）。
 *  注意：同上，getter 依赖「成员属性优先于扩展」命中注入成员。 */
val Application.shellExecutor: ShellExecutor
    get() = (this as RainyScreenShotApplication).shellExecutor

/** 收起快捷设置面板（磁贴点击路径防面板入镜，§13 实测：collapse 幂等）。 */
suspend fun Application.collapseQuickSettings(): Boolean {
    return (this as RainyScreenShotApplication).shellExecutor.collapseQuickSettings()
}

/** 磁贴是否在任务栏中（设置页状态显示用）。 */
suspend fun Application.hasQuickSettingsTile(tileClass: Class<*>): Boolean {
    return (this as RainyScreenShotApplication).shellExecutor
        .hasQuickSettingsTile(tileClass)
}

/** 把磁贴加入任务栏（设置页手动入口）。 */
suspend fun Application.addQuickSettingsTile(tileClass: Class<*>): Boolean {
    return (this as RainyScreenShotApplication).shellExecutor
        .addQuickSettingsTile(tileClass)
}

/** 从任务栏移除磁贴（设置页手动入口）。 */
suspend fun Application.removeQuickSettingsTile(tileClass: Class<*>): Boolean {
    return (this as RainyScreenShotApplication).shellExecutor
        .removeQuickSettingsTile(tileClass)
}