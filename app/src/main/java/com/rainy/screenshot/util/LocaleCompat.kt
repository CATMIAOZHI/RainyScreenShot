package com.rainy.screenshot.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用内语言切换工具（AppCompatDelegate.setApplicationLocales 封装）。
 *
 * - API 33+：写入系统级 per-app locale（与 Manifest localeConfig / 系统设置页
 *   双向同步），全进程生效（含磁贴标签、Service）
 * - API 31/32（minSdk 覆盖）：AppCompat autoStoreLocales 持久化 +
 *   AppCompatActivity 自动重建；非 Activity context（ViewModel / 悬浮球
 *   Service）经 [localized] 包装读取资源
 *
 * 支持值：null（跟随系统）/ "zh" / "zh-TW" / "en"（与 locales_config.xml 一致）
 */
object LocaleCompat {

    /**
     * 当前应用 locale 的 BCP-47 tag（"zh" / "zh-TW" / "en"）。
     * 未设置（跟随系统）返回 null。
     */
    fun currentAppLocaleTag(): String? {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) null else locales[0]?.toLanguageTag()
    }

    /**
     * 应用指定语言；tag 为 null 时清除设置、恢复跟随系统。
     *
     * API 33+ 经系统 per-app locale 生效；API<33 由 AppCompat 持久化
     * （autoStoreLocales）并在 AppCompatActivity 上自动重建生效。
     */
    fun applyAppLocale(tag: String?) {
        val locales = if (tag == null) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /**
     * 返回按应用 locale 解析资源的 context。
     *
     * 仅 API<33 且应用已设置语言时需要：此时进程资源配置未随
     * setApplicationLocales 更新，非 Activity 的 context（ApplicationContext /
     * Service）仍读系统语言。Activity context 自身已由 AppCompat 处理，
     * 包装幂等无害。
     */
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return context
        val target = locales[0] ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(target)
        return context.createConfigurationContext(config)
    }
}