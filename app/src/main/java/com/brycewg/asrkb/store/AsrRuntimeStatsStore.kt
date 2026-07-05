/**
 * 设备本地 ASR 请求耗时与本地模型加载耗时统计。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.util.Log
import com.brycewg.asrkb.asr.AsrVendor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object AsrRuntimeStatsStore {
    private const val TAG = "AsrRuntimeStats"
    private const val REQUEST_WINDOW_SIZE = 64
    private const val LOAD_WINDOW_SIZE = 32
    private const val MIN_REQUEST_SAMPLES = 5
    private const val OUTLIER_MULTIPLIER = 3.0
    private const val MIN_AUDIO_MS_FOR_NORMALIZATION = 1_000L

    fun recordRequest(
        prefs: Prefs,
        json: Json,
        vendor: AsrVendor,
        audioMs: Long,
        requestMs: Long,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (audioMs <= 0L || requestMs <= 0L) return
        val stats = getStats(prefs, json)
        val vendorStats = stats.vendors.getOrPut(vendor.id) { AsrRuntimeVendorStats() }
        vendorStats.requestSamples += AsrRuntimeRequestSample(
            audioMs = audioMs.coerceAtLeast(0L),
            requestMs = requestMs.coerceAtLeast(0L),
            timestampMs = timestampMs.coerceAtLeast(0L)
        )
        vendorStats.requestSamples = vendorStats.requestSamples
            .sortedBy { it.timestampMs }
            .takeLast(REQUEST_WINDOW_SIZE)
            .toMutableList()
        setStats(prefs, json, stats)
    }

    fun recordLoad(
        prefs: Prefs,
        json: Json,
        vendor: AsrVendor,
        loadMs: Long,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (loadMs <= 0L) return
        val stats = getStats(prefs, json)
        val vendorStats = stats.vendors.getOrPut(vendor.id) { AsrRuntimeVendorStats() }
        vendorStats.loadSamples += AsrRuntimeLoadSample(
            loadMs = loadMs.coerceAtLeast(0L),
            timestampMs = timestampMs.coerceAtLeast(0L)
        )
        vendorStats.loadSamples = vendorStats.loadSamples
            .sortedBy { it.timestampMs }
            .takeLast(LOAD_WINDOW_SIZE)
            .toMutableList()
        setStats(prefs, json, stats)
    }

    fun snapshot(
        prefs: Prefs,
        json: Json,
        vendor: AsrVendor,
        targetAudioMs: Long
    ): AsrRuntimeVendorSnapshot {
        val vendorStats = getStats(prefs, json).vendors[vendor.id] ?: AsrRuntimeVendorStats()
        return snapshot(
            vendorId = vendor.id,
            vendorStats = vendorStats,
            targetAudioMs = targetAudioMs
        )
    }

    fun clear(prefs: Prefs) {
        try {
            prefs.setPrefString(KEY_ASR_RUNTIME_STATS_JSON, "")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to clear ASR runtime stats", t)
        }
    }

    private fun getStats(prefs: Prefs, json: Json): AsrRuntimeStats {
        val raw = prefs.getPrefString(KEY_ASR_RUNTIME_STATS_JSON, "")
        if (raw.isBlank()) return AsrRuntimeStats()
        return try {
            json.decodeFromString<AsrRuntimeStats>(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse ASR runtime stats JSON", t)
            AsrRuntimeStats()
        }
    }

    private fun setStats(prefs: Prefs, json: Json, stats: AsrRuntimeStats) {
        try {
            prefs.setPrefString(KEY_ASR_RUNTIME_STATS_JSON, json.encodeToString(stats))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to serialize ASR runtime stats", t)
        }
    }

    private fun snapshot(
        vendorId: String,
        vendorStats: AsrRuntimeVendorStats,
        targetAudioMs: Long
    ): AsrRuntimeVendorSnapshot {
        val targetMs = targetAudioMs.coerceAtLeast(MIN_AUDIO_MS_FOR_NORMALIZATION)
        val normalized = vendorStats.requestSamples
            .mapNotNull { sample ->
                if (sample.audioMs <= 0L || sample.requestMs <= 0L) {
                    null
                } else {
                    val audio = sample.audioMs.coerceAtLeast(MIN_AUDIO_MS_FOR_NORMALIZATION)
                    (sample.requestMs.toDouble() / audio.toDouble() * targetMs.toDouble())
                        .toLong()
                        .coerceAtLeast(1L)
                }
            }
        val enough = normalized.size >= MIN_REQUEST_SAMPLES
        val clipped = if (enough) clipOutliers(normalized) else emptyList()
        val p50 = if (enough) percentileNearestRank(clipped, 0.50) else null
        val p90 = if (enough) percentileNearestRank(clipped, 0.90) else null
        val loadValues = vendorStats.loadSamples.mapNotNull { sample ->
            sample.loadMs.takeIf { it > 0L }
        }
        return AsrRuntimeVendorSnapshot(
            vendorId = vendorId,
            targetAudioMs = targetMs,
            requestSampleCount = normalized.size,
            hasEnoughRequestSamples = enough,
            p50RequestMs = p50,
            p90RequestMs = p90,
            slowRequestMs = p90,
            loadSampleCount = loadValues.size,
            latestLoadMs = vendorStats.loadSamples.maxByOrNull { it.timestampMs }?.loadMs,
            p50LoadMs = percentileNearestRank(loadValues, 0.50),
            p90LoadMs = percentileNearestRank(loadValues, 0.90)
        )
    }

    private fun clipOutliers(values: List<Long>): List<Long> {
        if (values.size < MIN_REQUEST_SAMPLES) return values.sorted()
        val sorted = values.sorted()
        val median = percentileNearestRank(sorted, 0.50) ?: return sorted
        if (median <= 0L) return sorted
        val high = (median.toDouble() * OUTLIER_MULTIPLIER).toLong().coerceAtLeast(median)
        val clipped = sorted.filter { it <= high }
        return clipped.ifEmpty { sorted }
    }

    private fun percentileNearestRank(values: List<Long>, percentile: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (percentile == 0.50) {
            return sorted[sorted.size / 2]
        }
        val rank = kotlin.math.ceil(percentile.coerceIn(0.0, 1.0) * sorted.size).toInt()
        val index = (rank - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}

@Serializable
internal data class AsrRuntimeStats(
    var vendors: MutableMap<String, AsrRuntimeVendorStats> = mutableMapOf()
)

@Serializable
internal data class AsrRuntimeVendorStats(
    var requestSamples: MutableList<AsrRuntimeRequestSample> = mutableListOf(),
    var loadSamples: MutableList<AsrRuntimeLoadSample> = mutableListOf()
)

@Serializable
internal data class AsrRuntimeRequestSample(
    val audioMs: Long,
    val requestMs: Long,
    val timestampMs: Long
)

@Serializable
internal data class AsrRuntimeLoadSample(
    val loadMs: Long,
    val timestampMs: Long
)

internal data class AsrRuntimeVendorSnapshot(
    val vendorId: String,
    val targetAudioMs: Long,
    val requestSampleCount: Int,
    val hasEnoughRequestSamples: Boolean,
    val p50RequestMs: Long?,
    val p90RequestMs: Long?,
    val slowRequestMs: Long?,
    val loadSampleCount: Int,
    val latestLoadMs: Long?,
    val p50LoadMs: Long?,
    val p90LoadMs: Long?
)
