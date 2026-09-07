package com.rainy.screenshot.capture

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * 静默截屏引擎（screencap 封装）。
 *
 * 原理（TECH_NOTES §2）：
 * - shell uid 直调 /system/bin/screencap，不经 MediaProjection，
 *   前台 App 无任何可感知信号（无弹窗、无回调、无状态栏图标）
 * - 输出到 App 私有外部目录（shell 写 → app 读，实测通畅，TECH_NOTES §6）
 *
 * 悬浮球处理：截屏前隐藏悬浮球（含快捷菜单），截屏后恢复——
 * 否则球和菜单会被拍进截图。所有截屏入口（磁贴/悬浮球/
 * 延时）都经 [captureTo]，在这里统一处理。
 */
@Singleton
class ScreenshotEngine @Inject constructor(
    private val shellExecutor: ShellExecutor
) {

    companion object {
        const val DIR_SCREENSHOTS = "Screenshots"
        const val PREFIX_SCREENSHOT = "rainy_ss"
    }

    /** 悬浮球隐藏嵌套计数（captureTo 单张包裹防闪烁；历史连拍整组包裹已随连拍入口移除）。 */
    private val hideDepth = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 隐藏悬浮球（截屏窗口期；主线程调度，嵌套计数）。
     * 第 1 层隐藏；内层调用幂等跳过。
     * NonCancellable：协程取消路径（磁贴/页面销毁）清理不失效。
     */
    private suspend fun hideBall() {
        if (hideDepth.getAndIncrement() == 0) {
            withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
                BallHiderRegistry.hide()
            }
        }
    }

    /**
     * 恢复悬浮球（截屏完成；归零才真正恢复）。
     * NonCancellable：finally 清理在已取消协程上仍执行（官方契约）。
     */
    private suspend fun showBall() {
        if (hideDepth.decrementAndGet() <= 0) {
            hideDepth.set(0)
            withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
                BallHiderRegistry.show()
            }
        }
    }

    /**
     * 执行一次静默截屏。
     *
     * @param config 截屏参数（含格式 PNG/RAW）
     * @return 生成的截图文件（App 私有目录）
     * @throws ShellException 环境问题 / 执行失败
     */
    suspend fun capture(config: ScreenshotConfig = ScreenshotConfig.DEFAULT): File {
        val fileName = CaptureFileNamer.timestampName(
            PREFIX_SCREENSHOT, config.fileExtension
        )
        val target = shellExecutor.newOutputFile(DIR_SCREENSHOTS, fileName)
        return captureTo(target, config)
    }

    /**
     * 截屏到指定文件（capture() 的统一实现，隐藏悬浮球包裹在此层）。
     *
     * 注意（B10 修复）：screencap -a 会给 FILENAME 自动加 `_0/_1` 后缀，
     * 校验固定路径必然失败。v1 暂不支持 allDisplays 组合，显式拒绝
     * 而不是静默产出校验失败；多屏支持在阶段 3 实现后缀文件收集。
     *
     * 格式（TECH_NOTES §2 实测）：
     * - PNG：`screencap -p` → PNG 文件
     * - RAW：无 `-p` → 16 字节头（width/height/format）+ 裸 RGBA_8888
     *   （3.2 节实测 1440x3200 → 18432016 字节 = 16 + 1440*3200*4）
     */
    suspend fun captureTo(target: File, config: ScreenshotConfig): File {
        if (config.allDisplays) {
            throw ShellException.Execution(
                -1,
                "allDisplays is not supported yet (screencap -a appends _N suffix to FILENAME); coming in stage 3"
            )
        }
        // 路径含空格的防御（私有目录不会含空格，但保持健壮）
        val path = target.absolutePath
        val displayArgs = buildString {
            config.displayId?.let { append("-d $it ") }
        }
        // RAW：不带 -p（16 字节头 + 裸像素）；PNG：-p
        val pngFlag = if (config.format == ScreenshotFormat.PNG) " -p" else ""
        // 隐藏悬浮球（含菜单）→ 截屏 → 恢复
        hideBall()
        try {
            val command = "screencap $displayArgs$pngFlag '$path' 1>'/dev/null' 2>&1"
            val result = shellExecutor.exec(command)

            if (result.exitCode != 0) {
                throw ShellException.Execution(result.exitCode, result.stderr)
            }
            // 校验：PNG 至少 1 字节；RAW 必须 ≥ 16 字节头 + 像素
            val minSize = if (config.format == ScreenshotFormat.RAW) 16L else 1L
            if (!target.exists() || target.length() < minSize) {
                throw ShellException.Execution(
                    -1,
                    "screencap produced no output at $path"
                )
            }
        } finally {
            showBall()
        }
        return target
    }
}