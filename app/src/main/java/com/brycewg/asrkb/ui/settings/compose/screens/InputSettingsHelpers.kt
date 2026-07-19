/**
 * 输入设置页的系统能力与标签辅助逻辑。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.ime.layout.KeyboardLayoutStore
import com.brycewg.asrkb.store.Prefs
import kotlin.math.roundToInt

internal data class ImeOption(val id: String, val label: String)

internal fun Context.languageOptions(): List<String> = listOf(
    getString(R.string.lang_follow_system),
    getString(R.string.lang_zh_cn),
    getString(R.string.lang_zh_tw),
    getString(R.string.lang_ja),
    getString(R.string.lang_en),
    getString(R.string.lang_ar)
)

internal fun Context.languageLabel(tag: String): String = languageOptions()[languageIndex(tag)]

internal fun languageTagForIndex(index: Int): String = when (index) {
    1 -> "zh-CN"
    2 -> "zh-TW"
    3 -> "ja"
    4 -> "en"
    5 -> "ar"
    else -> ""
}

internal fun normalizeLanguageTag(tag: String): String = languageTagForIndex(languageIndex(tag))

internal fun languageIndex(tag: String): Int = when (tag) {
    "zh", "zh-CN", "zh-Hans" -> 1
    "zh-TW", "zh-Hant" -> 2
    "ja" -> 3
    "en" -> 4
    "ar" -> 5
    else -> 0
}

internal fun Context.buildImeOptions(): List<ImeOption> {
    val imm = getSystemService(InputMethodManager::class.java)
    val options = mutableListOf(ImeOption("", getString(R.string.ime_switch_target_previous)))
    imm?.enabledInputMethodList.orEmpty()
        .filter { it.packageName != packageName }
        .forEach { info ->
            val label = info.loadLabel(packageManager)?.toString()?.trim()
            options += ImeOption(info.id, if (!label.isNullOrBlank()) label else info.id)
        }
    return options
}

internal fun Context.imeSwitchTargetLabel(prefs: Prefs): String {
    val options = buildImeOptions()
    return options.firstOrNull { it.id == prefs.imeSwitchTargetId }?.label
        ?: options.first().label
}

internal fun Context.extensionButtonsLabel(prefs: Prefs): String {
    val bundle = KeyboardLayoutStore.load(prefs)
    return getString(
        R.string.keyboard_layout_summary,
        bundle.main.gridSize.cols,
        bundle.main.gridSize.rows,
        bundle.aiEdit.gridSize.cols,
        bundle.aiEdit.gridSize.rows
    )
}

internal fun Context.hapticFeedbackStrengthLabel(level: Int): String {
    val resId = when (level) {
        Prefs.HAPTIC_FEEDBACK_LEVEL_OFF -> R.string.haptic_strength_off
        Prefs.HAPTIC_FEEDBACK_LEVEL_SYSTEM -> R.string.haptic_strength_system
        Prefs.HAPTIC_FEEDBACK_LEVEL_WEAK -> R.string.haptic_strength_weak
        Prefs.HAPTIC_FEEDBACK_LEVEL_LIGHT -> R.string.haptic_strength_light
        Prefs.HAPTIC_FEEDBACK_LEVEL_MEDIUM -> R.string.haptic_strength_medium
        Prefs.HAPTIC_FEEDBACK_LEVEL_STRONG -> R.string.haptic_strength_strong
        Prefs.HAPTIC_FEEDBACK_LEVEL_HEAVY -> R.string.haptic_strength_heavy
        else -> R.string.haptic_strength_system
    }
    return getString(resId)
}

internal fun Context.sendImeRefreshBroadcast() {
    sendBroadcast(
        Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI).apply {
            setPackage(packageName)
        }
    )
}

internal fun applyExcludeFromRecents(context: Context, enabled: Boolean) {
    val activity = context as? Activity ?: return
    val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
    activityManager?.appTasks?.forEach { it.setExcludeFromRecents(enabled) }
}

internal fun needsBluetoothConnectPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
    PackageManager.PERMISSION_GRANTED

internal fun Float.roundToStep(step: Int): Float = (this / step.toFloat()).roundToInt().coerceAtLeast(0) * step.toFloat()

internal fun Context.openExternalAidlReleaseUrl(url: String): Boolean {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        return true
    } catch (_: ActivityNotFoundException) {
        return false
    } catch (_: Throwable) {
        return false
    }
}
