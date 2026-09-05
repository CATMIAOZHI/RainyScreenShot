package com.rainy.screenshot.overlay

import android.content.Context
import com.rainy.screenshot.capture.ShellExecutor
import com.rainy.screenshot.data.local.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 悬浮球开关的统一控制器（首页/设置页/冷启动共用）。
 *
 * 职责：
 * - 开关状态持久化（SharedPreferences，进程死亡不丢——B1 修复）
 * - 权限获取：优先 Shizuku 静默授权（appops set），失败才引导系统设置页
 * - 竞态防护：异步授权期间用户可能又拨了开关，迟到的回调不覆盖
 *   用户最新意图（沿用设置页旧逻辑，审计记录 3）
 */
@Singleton
class FloatingBallController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val shellExecutor: ShellExecutor,
    private val settingsStore: SettingsStore
) {

    /** Singleton 生命周期=进程，scope 随进程存亡。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 开关状态流（StateFlow 镜像，供 Compose collect）。 */
    val enabled = settingsStore.floatingBallEnabledFlow

    /** 开关直读（冷启动等场景）。 */
    fun isEnabled(): Boolean = settingsStore.isFloatingBallEnabled()

    /**
     * 开关切换主入口（UI Switch onCheckedChange 调用）。
     *
     * 开 → 先持久化意图；权限已到直接启动服务，未到则 Shizuku
     * 静默授权后再启动（失败则开关回拨 + 引导系统设置页）。
     * 关 → 停服务。
     *
     * @param onNeedManualGrant 授权失败需要用户手动去系统设置页时的
     *        UI 回调（跳 ACTION_MANAGE_OVERLAY_PERMISSION；开关已在
     *        本方法内回拨 off，UI 无需再写状态）
     */
    fun toggle(on: Boolean, onNeedManualGrant: () -> Unit) {
        settingsStore.setFloatingBallEnabled(on)
        if (!on) {
            FloatingBallService.stop(appContext)
            return
        }
        if (android.provider.Settings.canDrawOverlays(appContext)) {
            FloatingBallService.start(appContext)
            return
        }
        // 权限未到：Shizuku 静默授权 → 成功后启动；失败引导系统设置
        scope.launch {
            val granted = runCatching {
                shellExecutor.grantOverlayPermission()
            }.getOrDefault(false)
            // 竞态防护：异步期间用户可能又拨了开关，只按最新意图收尾
            val stillDesired = settingsStore.isFloatingBallEnabled()
            if (granted && stillDesired) {
                FloatingBallService.start(appContext)
            } else if (!granted && stillDesired) {
                settingsStore.setFloatingBallEnabled(false)
                onNeedManualGrant()
            }
        }
    }

    /**
     * 冷启动自动恢复（Application.onCreate 调用）。
     * 开关开着 → 权限未到先静默授权（授权完成再拉服务，主链路
     * 一次成功，不依赖服务侧重试兜底）；授权失败也照常拉服务
     * （服务 addView 重试 30s 兜底，Shizuki 稍后可用时仍能出球）。
     */
    fun restoreOnBoot() {
        if (!settingsStore.isFloatingBallEnabled()) return
        if (android.provider.Settings.canDrawOverlays(appContext)) {
            runCatching { FloatingBallService.start(appContext) }
            return
        }
        scope.launch {
            runCatching { shellExecutor.grantOverlayPermission() }
            // 竞态复查：授权窗口内（1-2s）用户拨关则不再拉服务
            if (!settingsStore.isFloatingBallEnabled()) return@launch
            runCatching { FloatingBallService.start(appContext) }
        }
    }
}