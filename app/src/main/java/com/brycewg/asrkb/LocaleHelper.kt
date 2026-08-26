package com.brycewg.asrkb

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.store.KEY_APP_LANGUAGE_TAG
import java.util.Locale

object LocaleHelper {
    // 与 Prefs 的 SharedPreferences 文件名保持一致；此处直接读取，避免在
    // Service.attachBaseContext 阶段触发 Prefs 初始化副作用。
    private const val PREFS_FILE_NAME = "asr_prefs"
    private const val TAG = "LocaleHelper"

    fun wrap(newBase: Context): Context {
        val locales = resolveLocales(newBase)
        if (locales.isEmpty) return newBase
        val config = Configuration(newBase.resources.configuration)
        applyLocales(config, locales)
        return newBase.createConfigurationContext(config)
    }

    fun locale(context: Context): Locale {
        val locales = wrap(context).resources.configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }

    /**
     * 解析应用内语言，供 Service / 前台服务通知等无 Activity 的 Context 使用。
     *
     * 进程由保活服务拉起、尚未创建 Activity 时，[AppCompatDelegate.getApplicationLocales]
     * 可能仍为空；此时以 Prefs 中的语言设置为准。
     */
    private fun resolveLocales(context: Context): LocaleListCompat {
        val storedTag = normalizeLanguageTag(storedLanguageTag(context))
        if (storedTag.isNotBlank()) {
            return LocaleListCompat.forLanguageTags(storedTag)
        }
        val fromDelegate = AppCompatDelegate.getApplicationLocales()
        if (!fromDelegate.isEmpty) return fromDelegate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val fromManager = context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
            if (fromManager != null && !fromManager.isEmpty) {
                return LocaleListCompat.wrap(fromManager)
            }
        }
        return systemLocales(context)
    }

    private fun systemLocales(context: Context): LocaleListCompat {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val fromManager = context.getSystemService(LocaleManager::class.java)
                ?.systemLocales
            if (fromManager != null && !fromManager.isEmpty) {
                return LocaleListCompat.wrap(fromManager)
            }
        }
        val appContext = context.applicationContext ?: context
        return LocaleListCompat.wrap(appContext.resources.configuration.locales)
    }

    private fun storedLanguageTag(context: Context): String {
        return try {
            val appContext = context.applicationContext ?: context
            appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE_TAG, "")
                .orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read stored app language tag", t)
            ""
        }
    }

    private fun normalizeLanguageTag(tag: String): String = when (tag.trim().lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> tag.trim()
    }

    private fun applyLocales(config: Configuration, locales: LocaleListCompat) {
        if (locales.isEmpty) return
        val tags = locales.toLanguageTags()
        if (tags.isEmpty()) return
        val localeList = LocaleList.forLanguageTags(tags)
        if (localeList.isEmpty) return
        config.setLocales(localeList)
    }
}
