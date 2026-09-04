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
 * 截屏参数模型。
 *
 * @param displayId 指定 display-id（null = 主屏默认）
 * @param allDisplays 使用 -a 捕获所有活跃显示器（文件名自动加后缀 _0/_1）
 */
data class ScreenshotConfig(
    val displayId: Long? = null,
    val allDisplays: Boolean = false
) {
    companion object {
        val DEFAULT = ScreenshotConfig()
    }
}

/**
 * 录屏参数模型（对应 screenrecord v1.4 选项，实测参数表见 TECH_NOTES §3）。
 *
 * @param size 分辨率（null = 设备原生分辨率），形如 "1280x720"
 * @param bitRateMbps 码率 Mbps（null = 默认 8M）
 * @param timeLimitSec 时长上限秒（0 = 不限，默认 180）
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
    }

    /** 生成 screenrecord 参数段（不含输出文件名） */
    fun toArgs(): String = buildString {
        if (!size.isNullOrBlank()) append("--size $size ")
        bitRateMbps?.let { append("--bit-rate ${it}M ") }
        append("--time-limit $timeLimitSec ")
        displayId?.let { append("--display-id $it ") }
        if (bugreport) append("--bugreport ")
    }
}