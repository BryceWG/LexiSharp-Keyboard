/**
 * Bridge PCM recording observability summaries.
 *
 * 归属模块：imebridge
 */
package com.brycewg.asrkb.imebridge

import com.brycewg.asrkb.store.ApiLogStore

internal data class BridgePcmSessionLogRecord(
    val targetPackage: String,
    val sessionSummary: String,
    val operation: String,
    val resultCode: Int,
    val resultMessage: String,
    val pcmBytes: Long,
    val pcmDurationMs: Long,
    val frameCount: Int,
    val elapsedMs: Long,
    val hasInputConnection: Boolean,
    val isSensitiveField: Boolean,
    val isImeWindowVisible: Boolean,
    val supportsComposingPreview: Boolean,
    val supportsPcmRecording: Boolean
) {
    val success: Boolean get() = resultCode == ImeBridgePcmContract.RESULT_OK

    fun requestSummary(): String = buildString {
        append("package=").append(targetPackage)
        append("; session=").append(sessionSummary)
        append("; pcmBytes=").append(pcmBytes)
        append("; pcmDurationMs=").append(pcmDurationMs)
        append("; frameCount=").append(frameCount)
        append("; hasInputConnection=").append(hasInputConnection)
        append("; imeWindowVisible=").append(isImeWindowVisible)
        append("; sensitive=").append(isSensitiveField)
        append("; supportsPreview=").append(supportsComposingPreview)
        append("; supportsPcmRecording=").append(supportsPcmRecording)
    }

    fun responseSummary(): String = buildString {
        append("operation=").append(operation)
        append("; code=").append(resultCode)
        append("; message=").append(sanitizeReason(resultMessage))
        append("; elapsedMs=").append(elapsedMs)
    }
}

internal fun interface BridgePcmSessionLogSink {
    fun record(record: BridgePcmSessionLogRecord)
}

internal object NoopBridgePcmSessionLogSink : BridgePcmSessionLogSink {
    override fun record(record: BridgePcmSessionLogRecord) = Unit
}

internal object ApiLogBridgePcmSessionLogSink : BridgePcmSessionLogSink {
    override fun record(record: BridgePcmSessionLogRecord) {
        ApiLogStore.add(
            ApiLogStore.ApiLogRecord(
                category = "IME_BRIDGE_PCM",
                vendor = record.targetPackage,
                source = "ime_bridge",
                protocol = "Binder",
                method = record.operation,
                host = record.targetPackage,
                path = "/bridge_pcm/${record.operation}",
                requestSummary = record.requestSummary(),
                requestStructure = "binder bridge pcm session summary",
                responseSummary = record.responseSummary(),
                success = record.success,
                durationMs = record.elapsedMs,
                errorSummary = if (record.success) "" else sanitizeReason(record.resultMessage)
            )
        )
    }
}

internal class BridgePcmSessionSummary(
    private val targetPackage: String,
    private val sessionId: String,
    private val startedMs: Long,
    private val hasInputConnection: Boolean,
    private val isSensitiveField: Boolean,
    private val isImeWindowVisible: Boolean,
    private val supportsComposingPreview: Boolean,
    private val supportsPcmRecording: Boolean
) {
    private var pcmBytes: Long = 0
    private var pcmDurationMs: Long = 0
    private var frameCount: Int = 0

    fun addFrame(byteCount: Int, sampleRate: Int, channels: Int) {
        if (byteCount <= 0 || sampleRate <= 0 || channels <= 0) return
        pcmBytes += byteCount.toLong()
        pcmDurationMs += (byteCount.toLong() * 1000L) / (sampleRate.toLong() * channels * PCM_16_BYTES_PER_SAMPLE)
        frameCount += 1
    }

    fun record(operation: String, result: BridgePcmOperationResult, nowMs: Long): BridgePcmSessionLogRecord =
        BridgePcmSessionLogRecord(
            targetPackage = targetPackage,
            sessionSummary = summarizeSessionId(sessionId),
            operation = operation,
            resultCode = result.code,
            resultMessage = result.message,
            pcmBytes = pcmBytes,
            pcmDurationMs = pcmDurationMs,
            frameCount = frameCount,
            elapsedMs = (nowMs - startedMs).coerceAtLeast(0L),
            hasInputConnection = hasInputConnection,
            isSensitiveField = isSensitiveField,
            isImeWindowVisible = isImeWindowVisible,
            supportsComposingPreview = supportsComposingPreview,
            supportsPcmRecording = supportsPcmRecording
        )

    private companion object {
        private const val PCM_16_BYTES_PER_SAMPLE = 2L
    }
}

internal fun summarizeSessionId(sessionId: String): String =
    sessionId.take(8).ifEmpty { "empty" }

internal fun sanitizeReason(message: String): String {
    val normalized = message.lowercase()
    return when {
        normalized.contains("key") ||
            normalized.contains("token") ||
            normalized.contains("http://") ||
            normalized.contains("https://") ||
            normalized.contains("text=") ||
            normalized.contains("pcm=") ||
            normalized.contains("before_cursor") ||
            normalized.contains("after_cursor") -> "redacted"
        else -> message.take(120)
    }
}
