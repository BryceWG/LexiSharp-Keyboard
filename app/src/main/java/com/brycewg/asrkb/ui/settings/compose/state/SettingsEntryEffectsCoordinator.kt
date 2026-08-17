/**
 * 设置页入口副作用协调器。
 *
 * 负责首次引导与无障碍服务启用反馈。
 */
package com.brycewg.asrkb.ui.settings.compose.state

import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.setup.OnboardingGuideActivity

/**
 * 汇总进入设置页时需要触发的非 Compose UI 副作用。
 */
class SettingsEntryEffectsCoordinator(
    private val activity: BaseActivity,
    private val showSystemMessage: (Int, Int) -> Unit
) {
    private var wasAccessibilityEnabled = isAccessibilityServiceEnabled()

    fun onResume() {
        checkAccessibilityServiceJustEnabled()
        maybeAutoShowOnboardingGuideOnFirstOpen()
    }

    private fun openOnboardingGuide() {
        activity.startActivity(Intent(activity, OnboardingGuideActivity::class.java))
    }

    private fun maybeAutoShowOnboardingGuideOnFirstOpen() {
        try {
            val prefs = Prefs(activity)
            if (!prefs.hasShownOnboardingGuideV2Once) {
                // 进入即标记，避免因中途返回导致重复弹出。
                prefs.hasShownOnboardingGuideV2Once = true
                openOnboardingGuide()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to maybe auto show onboarding guide", t)
        }
    }

    private fun checkAccessibilityServiceJustEnabled() {
        val isNowEnabled = isAccessibilityServiceEnabled()

        if (!wasAccessibilityEnabled && isNowEnabled) {
            Log.d(TAG, "Accessibility service just enabled")
            showSystemMessage(R.string.settings_title, R.string.toast_accessibility_enabled)
        }

        wasAccessibilityEnabled = isNowEnabled
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName =
            "${activity.packageName}/com.brycewg.asrkb.ui.AsrAccessibilityService"
        val enabledServicesSetting = try {
            Settings.Secure.getString(
                activity.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get accessibility services", e)
            return false
        }

        Log.d(TAG, "Expected accessibility service: $expectedComponentName")
        Log.d(TAG, "Enabled accessibility services: $enabledServicesSetting")

        val result = enabledServicesSetting?.contains(expectedComponentName) == true
        Log.d(TAG, "Accessibility service enabled: $result")
        return result
    }

    private companion object {
        private const val TAG = "SettingsEntryEffects"
    }
}
