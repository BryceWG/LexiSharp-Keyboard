package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.R
import java.util.Locale

internal object AsrErrorMessageMapper {
    fun map(context: Context, raw: String): String? {
        if (raw.isEmpty()) return null

        val lower = raw.lowercase(Locale.ROOT)

        // 空结果
        val emptyHints = listOf(
            context.getString(R.string.error_asr_empty_result),
            context.getString(R.string.error_audio_empty),
            "empty asr result",
            "empty audio",
            "识别返回为空",
            "空音频",
        )
        if (containsAny(lower, emptyHints)) {
            return context.getString(R.string.asr_error_empty_result)
        }

        // HTTP 状态码
        val httpCode = Regex("HTTP\\s+(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        when (httpCode) {
            401 -> return context.getString(R.string.asr_error_auth_invalid)
            403 -> return context.getString(R.string.asr_error_auth_forbidden)
            429 -> return context.getString(R.string.asr_error_auth_invalid)
        }

        // WebSocket code
        val code = Regex("(?:ASR\\s*Error|status|code)\\s*(\\d{3})")
            .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        when (code) {
            401 -> return context.getString(R.string.asr_error_auth_invalid)
            403 -> return context.getString(R.string.asr_error_auth_forbidden)
        }

        // 录音权限
        val permHints = listOf(
            context.getString(R.string.error_record_permission_denied),
            context.getString(R.string.hint_need_permission),
            "record audio permission",
        )
        if (containsAny(lower, permHints)) {
            return context.getString(R.string.asr_error_mic_permission_denied)
        }

        // 麦克风被占用
        val micBusyHints = listOf(
            context.getString(R.string.error_audio_init_failed),
            "audio recorder busy",
            "resource busy",
            "in use",
            "device busy",
        )
        if (containsAny(lower, micBusyHints)) {
            return context.getString(R.string.asr_error_mic_in_use)
        }

        // SSL/TLS 握手失败
        if (lower.contains("handshake") ||
            lower.contains("sslhandshakeexception") ||
            lower.contains("trust anchor") ||
            lower.contains("certificate")
        ) {
            return context.getString(R.string.asr_error_network_handshake)
        }

        // 网络不可用
        if (lower.contains("unable to resolve host") ||
            lower.contains("no address associated") ||
            lower.contains("failed to connect") ||
            lower.contains("connect exception") ||
            lower.contains("network is unreachable") ||
            lower.contains("software caused connection abort") ||
            lower.contains("timeout") ||
            lower.contains("timed out")
        ) {
            return context.getString(R.string.asr_error_network_unavailable)
        }

        return null
    }

    private fun containsAny(lower: String, hints: List<String>): Boolean {
        return hints.any { hint ->
            hint.isNotBlank() && lower.contains(hint.lowercase(Locale.ROOT))
        }
    }
}
