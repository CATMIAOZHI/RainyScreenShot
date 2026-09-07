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
        // 磁贴不经此处注入：按水晴要求为纯手动添加（主页「任务栏磁贴」卡），
        // 避免用户未预期时任务栏被动变化
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