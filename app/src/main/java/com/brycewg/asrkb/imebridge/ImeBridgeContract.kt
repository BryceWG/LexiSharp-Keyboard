/**
 * 输入法桥接广播协议常量。
 *
 * 归属模块：imebridge
 */
package com.brycewg.asrkb.imebridge

internal object ImeBridgeContract {
    const val PROTOCOL_VERSION: Int = 1

    const val ACTION_QUERY_STATUS: String = "com.brycewg.asrkb.imebridge.action.QUERY_STATUS"
    const val ACTION_INSERT_TEXT: String = "com.brycewg.asrkb.imebridge.action.INSERT_TEXT"
    const val ACTION_BEGIN_SESSION: String = "com.brycewg.asrkb.imebridge.action.BEGIN_SESSION"
    const val ACTION_CANCEL_SESSION: String = "com.brycewg.asrkb.imebridge.action.CANCEL_SESSION"
    const val ACTION_SET_COMPOSING_TEXT: String =
        "com.brycewg.asrkb.imebridge.action.SET_COMPOSING_TEXT"
    const val ACTION_FINISH_COMPOSING_TEXT: String =
        "com.brycewg.asrkb.imebridge.action.FINISH_COMPOSING_TEXT"
    const val ACTION_IME_WINDOW_VISIBILITY_CHANGED: String =
        "com.brycewg.asrkb.imebridge.action.IME_WINDOW_VISIBILITY_CHANGED"

    const val EXTRA_PROTOCOL_VERSION: String = "protocol_version"
    const val EXTRA_REQUEST_ID: String = "request_id"
    const val EXTRA_SESSION_ID: String = "session_id"
    const val EXTRA_TEXT: String = "text"
    const val EXTRA_CURSOR_POSITION: String = "cursor_position"
    const val EXTRA_TARGET_PACKAGE: String = "target_package"
    const val EXTRA_HAS_INPUT_CONNECTION: String = "has_input_connection"
    const val EXTRA_IS_SENSITIVE_FIELD: String = "is_sensitive_field"
    const val EXTRA_IME_WINDOW_VISIBLE: String = "ime_window_visible"
    const val EXTRA_MODULE_VERSION: String = "module_version"
    const val EXTRA_SUPPORTS_INSERT_TEXT: String = "supports_insert_text"
    const val EXTRA_SUPPORTS_COMPOSING_PREVIEW: String = "supports_composing_preview"
    const val EXTRA_SUPPORTS_FINISH_COMPOSING_TEXT: String = "supports_finish_composing_text"
    const val EXTRA_SUPPORTS_SESSIONS: String = "supports_sessions"
    const val EXTRA_SUPPORTS_PCM_RECORDING: String = "supports_pcm_recording"
    const val EXTRA_ACTIVE_SESSION_ID: String = "active_session_id"
    const val EXTRA_LAST_OPERATION: String = "last_operation"
    const val EXTRA_LAST_RESULT_CODE: String = "last_result_code"
    const val EXTRA_LAST_ERROR: String = "last_error"
    const val EXTRA_MESSAGE: String = "message"

    const val RESULT_OK: Int = 1
    const val RESULT_NO_RECEIVER: Int = 0
    const val RESULT_PROTOCOL_MISMATCH: Int = -2
    const val RESULT_NO_ACTIVE_IME: Int = -3
    const val RESULT_NO_INPUT_CONNECTION: Int = -4
    const val RESULT_SENSITIVE_FIELD: Int = -5
    const val RESULT_COMMIT_FAILED: Int = -6
    const val RESULT_BAD_REQUEST: Int = -7
    const val RESULT_COMPOSING_FAILED: Int = -8
    const val RESULT_SESSION_MISMATCH: Int = -9
    const val RESULT_NO_CURRENT_IME: Int = -100
    const val RESULT_TIMEOUT: Int = -101
    const val RESULT_SEND_FAILED: Int = -102
}
