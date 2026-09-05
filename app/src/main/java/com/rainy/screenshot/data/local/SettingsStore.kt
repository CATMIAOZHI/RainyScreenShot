package com.rainy.screenshot.data.local

import android.content.Context
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

        // 悬浮球
        private val KEY_FLOATING_BALL = booleanPreferencesKey("floating_ball_enabled")

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

    /** 悬浮球开关流（默认关闭） */
    val floatingBallEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FLOATING_BALL] ?: false
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

    suspend fun setFloatingBallEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_FLOATING_BALL] = enabled
        }
    }

    /** 全部恢复默认（移除所有自定义键）。 */
    suspend fun resetAll() {
        dataStore.edit { it.clear() }
    }
}