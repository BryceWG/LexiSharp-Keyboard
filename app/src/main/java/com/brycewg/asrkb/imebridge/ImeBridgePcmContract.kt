/**
 * 输入法桥接 Push PCM 会话协议常量。
 *
 * 归属模块：imebridge
 */
package com.brycewg.asrkb.imebridge

internal object ImeBridgePcmContract {
    const val ACTION_BIND_SERVICE: String =
        "com.brycewg.asrkb.imebridge.action.BIND_PCM_SESSION_SERVICE"
    const val DESCRIPTOR: String =
        "com.brycewg.asrkb.imebridge.ImeBridgePcmSessionService"

    const val TRANSACTION_BEGIN: Int = android.os.IBinder.FIRST_CALL_TRANSACTION + 0
    const val TRANSACTION_WRITE_FRAME: Int = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_FINISH: Int = android.os.IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_CANCEL: Int = android.os.IBinder.FIRST_CALL_TRANSACTION + 3

    const val RESULT_OK: Int = 1
    const val RESULT_FEATURE_DISABLED: Int = -1
    const val RESULT_PACKAGE_MISMATCH: Int = -2
    const val RESULT_BRIDGE_UNAVAILABLE: Int = -3
    const val RESULT_NO_INPUT_CONNECTION: Int = -4
    const val RESULT_SENSITIVE_FIELD: Int = -5
    const val RESULT_BAD_REQUEST: Int = -6
    const val RESULT_BUSY: Int = -7
    const val RESULT_STALE_SESSION: Int = -8
    const val RESULT_UNSUPPORTED: Int = -9

    fun messageForCode(code: Int): String = when (code) {
        RESULT_OK -> "ok"
        RESULT_FEATURE_DISABLED -> "feature disabled"
        RESULT_PACKAGE_MISMATCH -> "package mismatch"
        RESULT_BRIDGE_UNAVAILABLE -> "bridge unavailable"
        RESULT_NO_INPUT_CONNECTION -> "no input connection"
        RESULT_SENSITIVE_FIELD -> "sensitive field"
        RESULT_BAD_REQUEST -> "bad request"
        RESULT_BUSY -> "busy"
        RESULT_STALE_SESSION -> "stale session"
        RESULT_UNSUPPORTED -> "unsupported"
        else -> "unknown: $code"
    }
}
