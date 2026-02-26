package com.brycewg.asrkb

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    fun wrap(newBase: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return newBase
        val config = Configuration(newBase.resources.configuration)
        applyLocales(config, locales)
        return newBase.createConfigurationContext(config)
    }

    private fun applyLocales(config: Configuration, locales: LocaleListCompat) {
        if (locales.isEmpty) return
        val tags = locales.toLanguageTags()
        if (tags.isEmpty()) return
        val localeList = LocaleList.forLanguageTags(tags)
        if (localeList.isEmpty) return
        config.setLocales(localeList)
        LocaleList.setDefault(localeList)
        Locale.setDefault(localeList[0])
    }
}
