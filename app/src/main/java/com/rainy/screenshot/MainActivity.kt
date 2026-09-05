package com.rainy.screenshot

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rainy.screenshot.capture.ShellExecutor
import com.rainy.screenshot.ui.RainyScreenShotNavHost
import com.rainy.screenshot.ui.components.RainyBackground
import com.rainy.screenshot.ui.theme.RainyScreenShotTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import rikka.shizuku.Shizuku

/**
 * APP 入口。
 *
 * 布局策略（对齐 RainyToken）：
 *  - 外层 RainyScreenShotTheme（品牌色）→ RainyBackground（樱粉渐变）→ NavHost
 *  - 每个页面自己处理 Scaffold 与 padding，避免双 Scaffold 嵌套
 *
 * 权限策略（阶段 3 增强）：
 *  - 启动即主动请求 Shizuku 授权（requestPermission），
 *    避免用户手动去 Shizuku 应用里操作。
 *  - 悬浮窗权限可通过 Shizuku shell 静默授予（appops set，见 ShellExecutor）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var shellExecutor: ShellExecutor

    private var shizukuPermissionListener: Shizuku.OnRequestPermissionResultListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 主动请求 Shizuku 授权：注册结果监听 + 若未授权则拉起请求。
        // 仅首次创建时发起（savedInstanceState == null 排除旋转重建重复弹框，
        // 审计记录 2：配置变更不应重复打扰）
        if (savedInstanceState == null) {
            registerShizukuPermissionRequest()
        }

        enableEdgeToEdge()
        setContent {
            RainyScreenShotTheme {
                RainyBackground {
                    RainyScreenShotNavHost()
                }
            }
        }
    }

    /**
     * 主动请求 Shizuku 授权。
     *
     * - Shizuku 已运行但本应用未授权 → 调用 requestPermission 拉起授权 UI。
     * - Shizuku 未运行 / 未安装 → 静默跳过（首页 EnvGuideCard 会引导）。
     */
    private fun registerShizukuPermissionRequest() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) return

        // 注册结果监听（回调线程为主线程，安全更新 UI）
        shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            // 结果由首页 SettingInfo/EnvGuideCard 的 refresh() 在下次进入时反映；
            // 此处不做 UI 强依赖，避免生命周期耦合
        }
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener!!)

        // 触发授权请求（授权对话框由 Shizuku 应用弹出）
        // 卫生级修复：pingBinder 与 requestPermission 间 binder 消失的极小窗口
        // 可抛 IllegalStateException（启动 crash），runCatching 兜底
        runCatching { shellExecutor.requestPermission(REQUEST_SHIZUKU_PERMISSION) }
    }

    override fun onDestroy() {
        shizukuPermissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
        shizukuPermissionListener = null
        super.onDestroy()
    }

    companion object {
        /** Shizuku 授权请求码 */
        const val REQUEST_SHIZUKU_PERMISSION = 10001
    }
}