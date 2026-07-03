/**
 * ASR 供应商可用性判断与分组工具：
 * - 在线供应商：按 API/鉴权配置是否完整判断
 * - 本地供应商：按模型文件是否已安装判断
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs

internal data class AsrVendorPartition(
    val configured: List<AsrVendor>,
    val unconfigured: List<AsrVendor>
)

internal enum class AsrVendorAvailabilityClassification {
    OnlineConfiguration,
    LocalModelReadiness
}

internal data class AsrVendorReadiness(
    val vendor: AsrVendor,
    val classification: AsrVendorAvailabilityClassification,
    /**
     * Future-proofing hook for vendors that have enough configuration/model state to be
     * reported as ready, but should still not be instantiated by shared factories.
     * Current online and local vendors are all constructible; local primary app paths
     * intentionally use their own prevalidation so missing-model UI remains reachable.
     */
    val isConstructible: Boolean,
    val onlineConfigured: Boolean?,
    val localModelReady: Boolean?
) {
    val isReady: Boolean
        get() = when (classification) {
            AsrVendorAvailabilityClassification.OnlineConfiguration -> onlineConfigured == true
            AsrVendorAvailabilityClassification.LocalModelReadiness -> localModelReady == true
        }

    val isUsable: Boolean
        get() = isConstructible && isReady
}

internal data class AsrVendorAvailabilityCheckers(
    val onlineConfiguration: (AsrVendor) -> Boolean,
    val localModelReadiness: (AsrVendor) -> Boolean
)

internal data class AsrOnlineConfigurationChecks(
    val hasSfKeys: () -> Boolean,
    val hasVendorKeys: (AsrVendor) -> Boolean
)

internal fun partitionAsrVendorsByConfigured(
    context: Context,
    prefs: Prefs,
    vendors: List<AsrVendor>
): AsrVendorPartition {
    val configured = mutableListOf<AsrVendor>()
    val unconfigured = mutableListOf<AsrVendor>()
    vendors.forEach { vendor ->
        if (isAsrVendorConfigured(context, prefs, vendor)) {
            configured.add(vendor)
        } else {
            unconfigured.add(vendor)
        }
    }
    return AsrVendorPartition(
        configured = configured,
        unconfigured = unconfigured
    )
}

internal fun isAsrVendorConfigured(context: Context, prefs: Prefs, vendor: AsrVendor): Boolean =
    checkAsrVendorAvailability(context, prefs, vendor).isUsable

internal fun checkAsrVendorAvailability(
    context: Context,
    prefs: Prefs,
    vendor: AsrVendor
): AsrVendorReadiness = try {
    checkAsrVendorAvailability(
        vendor = vendor,
        checkers = AsrVendorAvailabilityCheckers(
            onlineConfiguration = { checkedVendor ->
                isOnlineAsrVendorConfigured(
                    checkedVendor,
                    AsrOnlineConfigurationChecks(
                        hasSfKeys = { prefs.hasSfKeys() },
                        hasVendorKeys = { prefs.hasVendorKeys(it) }
                    )
                )
            },
            localModelReadiness = { checkedVendor ->
                AsrLocalVendorLifecycles.isModelReady(context, prefs, checkedVendor)
            }
        )
    )
} catch (t: Throwable) {
    Log.w(TAG, "Failed to check vendor availability: $vendor", t)
    unavailableReadiness(vendor)
}

internal fun checkAsrVendorAvailability(
    vendor: AsrVendor,
    checkers: AsrVendorAvailabilityCheckers
): AsrVendorReadiness {
    val classification = classifyAsrVendorAvailability(vendor)
    return when (classification) {
        AsrVendorAvailabilityClassification.OnlineConfiguration -> AsrVendorReadiness(
            vendor = vendor,
            classification = classification,
            isConstructible = true,
            onlineConfigured = checkers.onlineConfiguration(vendor),
            localModelReady = null
        )
        AsrVendorAvailabilityClassification.LocalModelReadiness -> AsrVendorReadiness(
            vendor = vendor,
            classification = classification,
            isConstructible = true,
            onlineConfigured = null,
            localModelReady = checkers.localModelReadiness(vendor)
        )
    }
}

internal fun classifyAsrVendorAvailability(vendor: AsrVendor): AsrVendorAvailabilityClassification =
    AsrVendorRegistry.descriptorFor(vendor).availabilityClassification

internal fun isOnlineAsrVendorConfigured(
    vendor: AsrVendor,
    checks: AsrOnlineConfigurationChecks
): Boolean = when (vendor) {
    AsrVendor.SiliconFlow -> checks.hasSfKeys()
    else -> checks.hasVendorKeys(vendor)
}

private fun unavailableReadiness(vendor: AsrVendor): AsrVendorReadiness {
    val classification = classifyAsrVendorAvailability(vendor)
    return when (classification) {
        AsrVendorAvailabilityClassification.OnlineConfiguration -> AsrVendorReadiness(
            vendor = vendor,
            classification = classification,
            isConstructible = true,
            onlineConfigured = false,
            localModelReady = null
        )
        AsrVendorAvailabilityClassification.LocalModelReadiness -> AsrVendorReadiness(
            vendor = vendor,
            classification = classification,
            isConstructible = true,
            onlineConfigured = null,
            localModelReady = false
        )
    }
}

private const val TAG = "AsrVendorAvailability"
