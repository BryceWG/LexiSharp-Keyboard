package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.R
import java.util.Locale

internal object AsrErrorMessageMapper {
    fun map(context: Context, raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw == context.getString(R.string.error_audio_empty_skipped)) return raw

        return when (classify(context, raw)) {
            AsrFailReasonCodes.EMPTY_RESULT -> context.getString(R.string.asr_error_empty_result)
            AsrFailReasonCodes.AUTH_INVALID -> context.getString(R.string.asr_error_auth_invalid)
            AsrFailReasonCodes.AUTH_FORBIDDEN -> context.getString(R.string.asr_error_auth_forbidden)
            AsrFailReasonCodes.MIC_PERMISSION -> context.getString(R.string.asr_error_mic_permission_denied)
            AsrFailReasonCodes.MIC_IN_USE -> context.getString(R.string.asr_error_mic_in_use)
            AsrFailReasonCodes.NETWORK_HANDSHAKE -> context.getString(R.string.asr_error_network_handshake)
            AsrFailReasonCodes.NETWORK -> context.getString(R.string.asr_error_network_unavailable)
            AsrFailReasonCodes.TIMEOUT -> context.getString(R.string.error_asr_timeout)
            else -> null
        }
    }

    fun classify(context: Context, raw: String): String {
        if (raw.isEmpty()) return AsrFailReasonCodes.UNKNOWN
        val lower = raw.lowercase(Locale.ROOT)

        if (isEmptyResult(context, raw) || isEmptyAudio(context, raw)) {
            return AsrFailReasonCodes.EMPTY_RESULT
        }

        // Realtime / streaming：音频太短导致提交失败
        if (lower.contains("buffer too small") ||
            lower.contains("expected at least 100ms") ||
            (lower.contains("commit") && lower.contains("input audio buffer") && lower.contains("too small"))
        ) {
            return AsrFailReasonCodes.EMPTY_RESULT
        }

        // HTTP 状态码
        val httpCode = Regex("HTTP\\s+(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        when (httpCode) {
            401, 429 -> return AsrFailReasonCodes.AUTH_INVALID
            403 -> return AsrFailReasonCodes.AUTH_FORBIDDEN
        }

        // WebSocket code
        val code = Regex("(?:ASR\\s*Error|status|code)\\s*(\\d{3})")
            .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        when (code) {
            401 -> return AsrFailReasonCodes.AUTH_INVALID
            403 -> return AsrFailReasonCodes.AUTH_FORBIDDEN
        }

        // 录音权限
        val permHints = listOf(
            context.getString(R.string.error_record_permission_denied),
            context.getString(R.string.hint_need_permission),
            "record audio permission"
        )
        if (containsAny(lower, permHints)) {
            return AsrFailReasonCodes.MIC_PERMISSION
        }

        // 麦克风被占用
        val micBusyHints = listOf(
            context.getString(R.string.error_audio_init_failed),
            "audio recorder busy",
            "resource busy",
            "in use",
            "device busy"
        )
        if (containsAny(lower, micBusyHints)) {
            return AsrFailReasonCodes.MIC_IN_USE
        }

        // SSL/TLS 握手失败
        if (lower.contains("handshake") ||
            lower.contains("sslhandshakeexception") ||
            lower.contains("trust anchor") ||
            lower.contains("certificate")
        ) {
            return AsrFailReasonCodes.NETWORK_HANDSHAKE
        }

        // 识别超时（须在网络兜底之前，避免英文 timed out 被当成 NETWORK）
        val timeoutHints = listOf(
            context.getString(R.string.error_asr_timeout),
            "recognition timed out",
            "asr timeout"
        )
        if (containsAny(lower, timeoutHints)) {
            return AsrFailReasonCodes.TIMEOUT
        }

        // 网络不可用（含连接/读超时）
        if (lower.contains("unable to resolve host") ||
            lower.contains("no address associated") ||
            lower.contains("failed to connect") ||
            lower.contains("connect exception") ||
            lower.contains("network is unreachable") ||
            lower.contains("software caused connection abort") ||
            lower.contains("timeout") ||
            lower.contains("timed out")
        ) {
            return AsrFailReasonCodes.NETWORK
        }

        return AsrFailReasonCodes.UNKNOWN
    }

    fun isEmptyResult(context: Context, raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        return containsAny(
            lower,
            listOf(
                context.getString(R.string.error_asr_empty_result),
                context.getString(R.string.asr_error_empty_result),
                "empty asr result",
                "empty asr",
                "识别返回为空"
            )
        )
    }

    private fun isEmptyAudio(context: Context, raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT)
        return containsAny(
            lower,
            listOf(
                context.getString(R.string.error_audio_empty),
                "empty audio",
                "空音频"
            )
        )
    }

    private fun containsAny(lower: String, hints: List<String>): Boolean = hints.any { hint ->
        hint.isNotBlank() && lower.contains(hint.lowercase(Locale.ROOT))
    }
}
