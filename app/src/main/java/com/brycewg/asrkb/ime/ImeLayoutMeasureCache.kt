/**
 * 持久化同设备 IME 首帧高度预测，避免 View 尚未布局时重复主动测量。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.Context
import android.view.View
import androidx.core.content.edit
import com.brycewg.asrkb.store.Prefs
import java.security.MessageDigest

internal class ImeLayoutMeasureCache(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var lastWrittenEntry: Pair<String, Int>? = null

    fun read(root: View, prefs: Prefs, decorWidth: Int, decorHeight: Int, bottomInset: Int): Int? {
        val key = entryKey(root, prefs, decorWidth, decorHeight, bottomInset)
        return storage.getInt(key, 0).takeIf { it in 1..decorHeight }
    }

    fun record(
        root: View,
        prefs: Prefs,
        decorWidth: Int,
        decorHeight: Int,
        bottomInset: Int,
        measuredHeight: Int
    ) {
        if (measuredHeight !in 1..decorHeight) return
        val key = entryKey(root, prefs, decorWidth, decorHeight, bottomInset)
        val entry = key to measuredHeight
        if (lastWrittenEntry == entry || storage.getInt(key, 0) == measuredHeight) {
            lastWrittenEntry = entry
            return
        }
        lastWrittenEntry = entry
        val entryKeys = LinkedHashSet(storage.getStringSet(KEY_ENTRIES, emptySet()).orEmpty())
        entryKeys.remove(key)
        while (entryKeys.size >= MAX_ENTRIES) {
            entryKeys.remove(entryKeys.first())
        }
        entryKeys += key
        storage.edit {
            storage.getStringSet(KEY_ENTRIES, emptySet()).orEmpty()
                .filterNot(entryKeys::contains)
                .forEach(::remove)
            putInt(key, measuredHeight)
            putStringSet(KEY_ENTRIES, entryKeys)
        }
    }

    private fun entryKey(
        root: View,
        prefs: Prefs,
        decorWidth: Int,
        decorHeight: Int,
        bottomInset: Int
    ): String {
        val resources = root.resources
        val config = resources.configuration
        val metrics = resources.displayMetrics
        val signature = buildString {
            append(CACHE_VERSION)
            append('|').append(decorWidth)
            append('x').append(decorHeight)
            append('|').append(config.orientation)
            append('|').append(config.screenWidthDp)
            append('x').append(config.screenHeightDp)
            append('|').append(metrics.densityDpi)
            append('|').append(config.fontScale)
            append('|').append(prefs.keyboardHeightTier)
            append('|').append(prefs.keyboardBottomPaddingDp)
            append('|').append(prefs.customKeyboardLayoutsJson)
            append('|').append(prefs.extBtn1.id)
            append('|').append(prefs.extBtn2.id)
            append('|').append(prefs.extBtn3.id)
            append('|').append(prefs.extBtn4.id)
            append('|').append(resources.navigationMode())
            append('|').append(bottomInset.coerceAtLeast(0))
        }
        return "height_${signature.sha256()}"
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun android.content.res.Resources.navigationMode(): Int {
        val id = getIdentifier("config_navBarInteractionMode", "integer", "android")
        return if (id == 0) 0 else runCatching { getInteger(id) }.getOrDefault(0)
    }

    private companion object {
        private const val PREFS_NAME = "ime_layout_measure_cache"
        private const val KEY_ENTRIES = "entries"
        private const val CACHE_VERSION = 1
        private const val MAX_ENTRIES = 8
    }
}
