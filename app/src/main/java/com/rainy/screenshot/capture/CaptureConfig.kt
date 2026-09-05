package com.rainy.screenshot.capture

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件命名工具（App + shell 共用的命名约定）。
 */
object CaptureFileNamer {

    private val format = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /** 时间戳文件名（毫秒精度，避免连拍冲突） */
    fun timestampName(prefix: String, ext: String): String {
        val ts = format.format(Date(System.currentTimeMillis()))
        return "${prefix}_$ts.$ext"
    }

    /** 已运行时长格式化 mm:ss（UI 展示用） */
    fun formatDuration(millis: Long): String {
        val totalSec = millis / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/**
 * 截屏输出格式（实测依据 TECH_NOTES §2.5：RAW 输出 16 字节头 + 裸 RGBA）。
 */
enum class ScreenshotFormat {
    /** PNG（默认，screencap -p） */
    PNG,
    /** 原始像素（screencap 无 -p，16 字节头 w/h/format + RGBA_8888，文件巨大） */
    RAW
}

/**
 * 截屏参数模型。
 *
 * @param displayId 指定 display-id（null = 主屏默认）
 * @param allDisplays 使用 -a 捕获所有活跃显示器（文件名自动加后缀 _0/_1）
 * @param format 输出格式（PNG / RAW，静态截屏用）
 */
data class ScreenshotConfig(
    val displayId: Long? = null,
    val allDisplays: Boolean = false,
    val format: ScreenshotFormat = ScreenshotFormat.PNG
) {
    companion object {
        val DEFAULT = ScreenshotConfig()
    }

    /** 输出文件扩展名（与 [format] 匹配，RAW 必须 .raw 才能触发无 -p 模式）。 */
    val fileExtension: String
        get() = when (format) {
            ScreenshotFormat.PNG -> "png"
            ScreenshotFormat.RAW -> "raw"
        }
}

/**
 * 录屏参数模型（对应 screenrecord v1.4 选项，实测参数表见 TECH_NOTES §3）。
 *
 * @param size 分辨率（null = 设备原生分辨率），形如 "1280x720"
 * @param bitRateMbps 码率 Mbps（null = 默认 8M）
 * @param timeLimitSec 时长上限秒（0 = 不限，实测 `--time-limit 0` 合法，
 *                     screenrecord help：Set to 0 to remove the time limit）
 * @param displayId 指定 display-id（null = 主屏）
 * @param bugreport 叠加 bugreport 信息（时间戳 overlay）
 */
data class RecordConfig(
    val size: String? = null,
    val bitRateMbps: Int? = 8,
    val timeLimitSec: Int = 180,
    val displayId: Long? = null,
    val bugreport: Boolean = false
) {
    companion object {
        val DEFAULT = RecordConfig()

        /** 不支持的值：bitrate 必须是正数。 */
        const val BITRATE_MIN_MBPS = 1

        /** timeLimitSec 允许的最大值（7 天），防御性上限。 */
        const val TIME_LIMIT_MAX_SEC = 604_800
    }

    /** 生成 screenrecord 参数段（不含输出文件名） */
    fun toArgs(): String = buildString {
        if (!size.isNullOrBlank()) append("--size $size ")
        bitRateMbps?.let { append("--bit-rate ${it}M ") }
        // N7 边界：0 = 不限时长（实测 --time-limit 0 被 screenrecord 接受），
        // 直接透传即可，勿过滤
        append("--time-limit $timeLimitSec ")
        displayId?.let { append("--display-id $it ") }
        if (bugreport) append("--bugreport ")
    }

    /** 规范化：bitrate 下限 1，时长 clamp 到 [0, TIME_LIMIT_MAX_SEC]。 */
    fun normalized(): RecordConfig = copy(
        bitRateMbps = bitRateMbps?.coerceAtLeast(BITRATE_MIN_MBPS),
        timeLimitSec = timeLimitSec.coerceIn(0, TIME_LIMIT_MAX_SEC)
    )
}