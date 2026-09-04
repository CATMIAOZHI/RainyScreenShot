package com.rainy.screenshot.capture

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 静默截屏引擎（screencap 封装）。
 *
 * 原理（TECH_NOTES §2）：
 * - shell uid 直调 /system/bin/screencap，不经 MediaProjection，
 *   前台 App 无任何可感知信号（无弹窗、无回调、无状态栏图标）
 * - 输出到 App 私有外部目录（shell 写 → app 读，实测通畅，TECH_NOTES §6）
 */
@Singleton
class ScreenshotEngine @Inject constructor(
    private val shellExecutor: ShellExecutor
) {

    companion object {
        const val DIR_SCREENSHOTS = "Screenshots"
        const val PREFIX_SCREENSHOT = "rainy_ss"
    }

    /**
     * 执行一次静默截屏。
     *
     * @param config 截屏参数
     * @return 生成的 PNG 文件（App 私有目录）
     * @throws ShellException 环境问题 / 执行失败
     */
    suspend fun capture(config: ScreenshotConfig = ScreenshotConfig.DEFAULT): File {
        val fileName = CaptureFileNamer.timestampName(PREFIX_SCREENSHOT, "png")
        val target = shellExecutor.newOutputFile(DIR_SCREENSHOTS, fileName)
        return captureTo(target, config)
    }

    /**
 * 截屏到指定文件（连拍/批量场景复用）。
     *
     * 注意（B10 修复）：screencap -a 会给 FILENAME 自动加 `_0/_1` 后缀，
     * 校验固定路径必然失败。v1 暂不支持 allDisplays 组合，显式拒绝
     * 而不是静默产出校验失败；多屏支持在阶段 3 实现后缀文件收集。
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
        val command = "screencap $displayArgs-p '$path' 1>'/dev/null' 2>&1"
        val result = shellExecutor.exec(command)

        if (result.exitCode != 0) {
            throw ShellException.Execution(result.exitCode, result.stderr)
        }
        if (!target.exists() || target.length() == 0L) {
            throw ShellException.Execution(
                -1,
                "screencap produced no output at $path"
            )
        }
        return target
    }
}