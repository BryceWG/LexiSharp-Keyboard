// Preflights primary ASR network availability before starting online engines.
package com.brycewg.asrkb.asr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

internal object AsrPrimaryNetworkGate {
    const val NO_NETWORK_PRIMARY_ERROR = "Network unavailable before primary ASR request"

    fun preflightEvent(
        primaryVendor: AsrVendor,
        networkAvailable: Boolean,
        message: String = NO_NETWORK_PRIMARY_ERROR
    ): AsrBackupArbitrationEvent.PrimaryError? {
        if (networkAvailable) return null
        if (!isOnlinePrimary(primaryVendor)) return null
        return AsrBackupArbitrationEvent.PrimaryError(
            message = message,
            strategy = AsrPrimaryErrorStrategy.ImmediateFailover
        )
    }

    fun isNetworkAvailable(context: Context): Boolean = try {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to check network availability for ASR primary gate", t)
        true
    }

    private fun isOnlinePrimary(vendor: AsrVendor): Boolean = classifyAsrVendorAvailability(vendor) == AsrVendorAvailabilityClassification.OnlineConfiguration
}

private const val TAG = "AsrPrimaryNetworkGate"
