package com.rainy.screenshot.capture

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

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
     * 截屏到指定文件（连拍/批量场景复用）。
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
        return target
    }

    /**
     * 连拍（阶段 3-4）：按 [count] 张、[intervalMs] 间隔连续截屏。
     *
     * 每张独立命名（时间戳 + 序号），失败策略：单张失败不中断整组
     * （记录失败张数），至少成功 1 张即视为组成功。
     *
     * @param config 截屏参数（格式/display-id 共用）
     * @param count 张数（1..10）
     * @param intervalMs 间隔毫秒（>= 500，screencap 单帧耗时 + 余量）
     * @return 成功产出的文件列表
     * @throws ShellException 全部失败或参数非法
     */
    suspend fun captureSeries(
        config: ScreenshotConfig,
        count: Int,
        intervalMs: Long = 1_000L
    ): List<File> {
        require(count in 1..10) { "count must be 1..10" }
        require(intervalMs >= 500L) { "intervalMs must be >= 500" }

        val results = mutableListOf<File>()
        val failures = StringBuilder()
        for (i in 1..count) {
            val fileName = CaptureFileNamer.timestampName(
                "${PREFIX_SCREENSHOT}_${i.toString().padStart(2, '0')}",
                config.fileExtension
            )
            val target = shellExecutor.newOutputFile(DIR_SCREENSHOTS, fileName)
            try {
                captureTo(target, config)
                results += target
            } catch (e: Exception) {
                if (failures.isNotEmpty()) failures.append("; ")
                failures.append("第${i}张: ${e.message}")
            }
            if (i < count) delay(intervalMs)
        }
        if (results.isEmpty()) {
            throw ShellException.Execution(-1, "连拍全部失败: $failures")
        }
        return results
    }
}