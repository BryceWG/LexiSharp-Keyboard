/**
 * 非流式识别调用链路延迟探针。仅 verbose；只记字节数与毫秒，不记文本和 key。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.os.SystemClock
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.concurrent.atomic.AtomicLong

internal object AsrCallLatencyProbe {
    private val stopOriginElapsedMs = AtomicLong(0L)

    fun reset() {
        stopOriginElapsedMs.set(0L)
    }

    fun markStop(reason: String, path: String) {
        val now = elapsedRealtimeMs()
        if (!stopOriginElapsedMs.compareAndSet(0L, now)) return
        emit(
            "t_stop_mark",
            mapOf(
                "reason" to reason,
                "path" to path,
                "since_stop_ms" to 0L
            )
        )
    }

    fun log(event: String, data: Map<String, Any?> = emptyMap()) {
        val merged = LinkedHashMap<String, Any?>(data.size + 1)
        merged.putAll(data)
        sinceStopMs()?.let { merged["since_stop_ms"] = it }
        emit(event, merged)
    }

    private fun sinceStopMs(): Long? {
        val origin = stopOriginElapsedMs.get()
        if (origin <= 0L) return null
        return (elapsedRealtimeMs() - origin).coerceAtLeast(0L)
    }

    private fun emit(event: String, data: Map<String, Any?>) {
        try {
            DebugLogManager.log(category = "asr", event = event, data = data)
        } catch (_: Throwable) {
        }
    }

    private fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
