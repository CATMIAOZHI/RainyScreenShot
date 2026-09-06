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

    /**
     * 解析时间戳文件名中的录制/截屏起点（恢复场景用）。
     *
     * 文件名本身就编码了精确起点（[timestampName] 同一 format 反解），
     * 比用文件 mtime 近似（录制中 mtime 每秒刷新 ≈ 收养时刻）精确。
     *
     * @return 起点毫秒时间戳；文件名不符合约定格式时 null（调用方回退 mtime）
     */
    fun parseTimestampName(fileName: String): Long? = runCatching {
        // rainy_rec_yyyyMMdd_HHmmss_SSS.mp4 → stem = rainy_rec_yyyyMMdd_HHmmss_SSS
        // 时间戳 = 最后 3 个 '_' 段（前缀自身可含 '_'，故从右侧取定长段）
        val stem = fileName.substringBeforeLast('.')
        val parts = stem.split('_')
        require(parts.size >= 4) // 前缀(≥1 段) + 时间戳 3 段
        val ts = parts.takeLast(3).joinToString("_")
        format.parse(ts)?.time
    }.getOrNull()

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
 * @param timeLimitSec 时长上限秒。**0 = 「不限」档位**——但这是 App 死亡场景下
 *                     唯一的安全刹车（shell 侧 screenrecord 独立存活，App 被杀
 *                     后无人能叫停它），因此 [normalized] 会把 0 封顶为
 *                     [UNLIMITED_CEILING_SEC] 再下发，进程级保证有限录制。
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

        /**
         * 「不限」档位的实际封顶（1 小时）。
         *
         * App 被杀后 screenrecord 独立存活（会话保活特性），--time-limit 是
         * 唯一进程级刹车；0（真不限）在 App 死亡场景 = 无界写盘敞口
         * （32Mbps ≈ 14.4GB/h，直至磁盘写满）。1h 覆盖 UI 全部现实场景
         * （最长档 10m + 「不限」≈ 远超 10m 的诉求），用户在场可随时手动停。
         */
        const val UNLIMITED_CEILING_SEC = 3_600
    }

    /** 生成 screenrecord 参数段（不含输出文件名） */
    fun toArgs(): String = buildString {
        if (!size.isNullOrBlank()) append("--size $size ")
        bitRateMbps?.let { append("--bit-rate ${it}M ") }
        // 注意：此处直接使用 [timeLimitSec]。正常链路所有调用方都先过
        // normalized()（0 已被封顶），勿再传 0 —— 见 RecordConfig KDoc。
        append("--time-limit $timeLimitSec ")
        displayId?.let { append("--display-id $it ") }
        if (bugreport) append("--bugreport ")
    }

    /**
     * 规范化：bitrate 下限 1；时长 0 封顶为 [UNLIMITED_CEILING_SEC]，
     * 其余 clamp 到 [1, TIME_LIMIT_MAX_SEC]。
     *
     * 所有消费点（首页/磁贴/悬浮球/restore）统一走本函数，单点生效。
     */
    fun normalized(): RecordConfig = copy(
        bitRateMbps = bitRateMbps?.coerceAtLeast(BITRATE_MIN_MBPS),
        timeLimitSec = if (timeLimitSec == 0) {
            UNLIMITED_CEILING_SEC
        } else {
            timeLimitSec.coerceIn(1, TIME_LIMIT_MAX_SEC)
        }
    )
}
