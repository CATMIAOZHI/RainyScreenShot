package com.rainy.screenshot.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rainy.screenshot.capture.RecordConfig
import com.rainy.screenshot.capture.ScreenshotConfig
import com.rainy.screenshot.capture.ScreenshotFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 全局偏好 DataStore 委托（单实例）。 */
private val Context.rainySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "rainy_settings"
)

/**
 * 设置持久化（阶段 3：截屏/录屏参数自定义）。
 *
 * 存储模型：
 * - 录屏：码率/分辨率/时长/display-id/bugreport（对应 [RecordConfig]）
 * - 截屏：格式（PNG/RAW）/display-id（对应 [ScreenshotConfig]）
 *
 * 约定：空字符串 = 跟随默认值（null 参数），时长 0 = 不限时长。
 */
/** 悬浮球开关存储（SharedPreferences，B1 修复）。
 *
 * 为何不用 DataStore：调试期 pm install -r / force-stop 杀进程频繁，
 * DataStore 写入中断 → 文件损坏 → ReplaceFileCorruptionHandler 恢复空数据
 * （真机实锤：preferences_pb 0 字节，开关状态连同全部设置丢失，重启后
 * 球不自动出现）。SharedPreferences 由系统落盘，commit() 同步写 + 系统
 * 落盘策略，进程死亡不丢（仅极端断电可能丢最后一次写，可接受）。
 * 截屏/录屏参数继续用 DataStore（参数丢失只影响一次录制的质量，无
 * 「功能消失」的体感）。
 */
private val Context.ballPrefs: SharedPreferences
    get() = getSharedPreferences("ball_state", Context.MODE_PRIVATE)

/** 悬浮球开关（SharedPreferences 持久化，进程死亡不丢）。 */
var Context.floatingBallEnabled: Boolean
    get() = ballPrefs.getBoolean(KEY, false)
    set(value) {
        ballPrefs.edit().putBoolean(KEY, value).commit()
    }
private const val KEY = "floating_ball_enabled"

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dataStore: DataStore<Preferences> = context.rainySettingsDataStore

    companion object {
        // 录屏
        private val KEY_REC_BITRATE = intPreferencesKey("rec_bitrate_mbps")
        private val KEY_REC_SIZE = stringPreferencesKey("rec_size")
        private val KEY_REC_TIME_LIMIT = intPreferencesKey("rec_time_limit_sec")
        private val KEY_REC_DISPLAY_ID = stringPreferencesKey("rec_display_id")
        private val KEY_REC_BUGREPORT = booleanPreferencesKey("rec_bugreport")

        // 截屏
        private val KEY_SS_FORMAT = stringPreferencesKey("ss_format")
        private val KEY_SS_DISPLAY_ID = stringPreferencesKey("ss_display_id")

        /** 码率可选档位（Mbps） */
        val BITRATE_OPTIONS = listOf(1, 4, 8, 12, 16, 24, 32)

        /** 时长可选档位（秒），0 = 不限 */
        val TIME_LIMIT_OPTIONS = listOf(0, 15, 30, 60, 180, 300, 600)
    }

    /* ── 读（Flow 形式，UI 侧 collect） ── */

    /** 录屏参数流（默认值兜底） */
    val recordConfigFlow: Flow<RecordConfig> = dataStore.data.map { prefs ->
        RecordConfig(
            bitRateMbps = prefs[KEY_REC_BITRATE] ?: RecordConfig.DEFAULT.bitRateMbps,
            size = prefs[KEY_REC_SIZE]?.takeIf { it.isNotBlank() },
            timeLimitSec = prefs[KEY_REC_TIME_LIMIT] ?: RecordConfig.DEFAULT.timeLimitSec,
            displayId = prefs[KEY_REC_DISPLAY_ID]?.toLongOrNull(),
            bugreport = prefs[KEY_REC_BUGREPORT] ?: RecordConfig.DEFAULT.bugreport
        )
    }

    /** 截屏参数流（默认值兜底） */
    val screenshotConfigFlow: Flow<ScreenshotConfig> = dataStore.data.map { prefs ->
        ScreenshotConfig(
            displayId = prefs[KEY_SS_DISPLAY_ID]?.toLongOrNull(),
            format = runCatching {
                ScreenshotFormat.valueOf(
                    prefs[KEY_SS_FORMAT] ?: ScreenshotFormat.PNG.name
                )
            }.getOrDefault(ScreenshotFormat.PNG)
        )
    }

    /** 悬浮球开关内存镜像（与 SharedPreferences 真值源同步，供 Compose collect）。 */
    private val _ballEnabled = kotlinx.coroutines.flow.MutableStateFlow(context.floatingBallEnabled)

    /** 悬浮球开关流。 */
    val floatingBallEnabledFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _ballEnabled

    /** 悬浮球开关直读（Application 冷启动等非 Flow 场景）。 */
    fun isFloatingBallEnabled(): Boolean = context.floatingBallEnabled

    /** 悬浮球开关写入（commit 同步落盘 + 流镜像同步）。 */
    fun setFloatingBallEnabled(enabled: Boolean) {
        context.floatingBallEnabled = enabled
        _ballEnabled.value = enabled
    }

    /* ── 写（挂起函数，设置页调用） ── */

    suspend fun setRecordConfig(config: RecordConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_REC_BITRATE] = config.bitRateMbps ?: RecordConfig.DEFAULT.bitRateMbps!!
            prefs[KEY_REC_SIZE] = config.size ?: ""
            prefs[KEY_REC_TIME_LIMIT] = config.timeLimitSec
            config.displayId?.let { prefs[KEY_REC_DISPLAY_ID] = it.toString() }
                ?: prefs.remove(KEY_REC_DISPLAY_ID)
            prefs[KEY_REC_BUGREPORT] = config.bugreport
        }
    }

    suspend fun setScreenshotConfig(config: ScreenshotConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_SS_FORMAT] = config.format.name
            config.displayId?.let { prefs[KEY_SS_DISPLAY_ID] = it.toString() }
                ?: prefs.remove(KEY_SS_DISPLAY_ID)
        }
    }

    /** 全部恢复默认（移除所有自定义键；悬浮球开关存 SharedPreferences 不受影响）。 */
    suspend fun resetAll() {
        dataStore.edit { it.clear() }
    }
}