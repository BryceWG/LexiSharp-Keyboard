/**
 * Compose 其他设置页状态模型。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.KeepAliveNotificationClick

internal data class PunctuationFields(
    val punct1: String,
    val punct2: String,
    val punct3: String,
    val punct4: String
)

internal fun persistPunctuationIfChanged(prefs: Prefs, fields: PunctuationFields) {
    if (prefs.punct1 != fields.punct1) prefs.punct1 = fields.punct1
    if (prefs.punct2 != fields.punct2) prefs.punct2 = fields.punct2
    if (prefs.punct3 != fields.punct3) prefs.punct3 = fields.punct3
    if (prefs.punct4 != fields.punct4) prefs.punct4 = fields.punct4
}

internal data class OtherSettingsUiState(
    val keepAliveEnabled: Boolean,
    val keepAliveNotificationClickRoute: String,
    val privilegedKeepAliveEnabled: Boolean,
    val disableAsrHistory: Boolean,
    val audioHistoryRetentionCount: Int,
    val disableUsageStats: Boolean,
    val dataCollectionEnabled: Boolean
) {
    companion object {
        fun fromPrefs(prefs: Prefs): OtherSettingsUiState = OtherSettingsUiState(
            keepAliveEnabled = prefs.floatingKeepAliveEnabled,
            keepAliveNotificationClickRoute = KeepAliveNotificationClick.routeFromPrefs(prefs).id,
            privilegedKeepAliveEnabled = prefs.floatingKeepAlivePrivilegedEnabled,
            disableAsrHistory = prefs.disableAsrHistory,
            audioHistoryRetentionCount = prefs.audioHistoryRetentionCount,
            disableUsageStats = prefs.disableUsageStats,
            dataCollectionEnabled = prefs.dataCollectionEnabled
        )
    }
}
