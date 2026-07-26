/**
 * Bridge → App Clipboard Sync Runtime 激活协议常量。
 *
 * 归属模块：imebridge / clipboard
 * 不得在协议中传递 SyncClipboard 服务器地址或凭证。
 */
package com.brycewg.asrkb.imebridge

internal object ImeBridgeClipboardSyncContract {
    const val HOOK_PROTOCOL_VERSION: Int = 1
    const val NATIVE_IME_PROTOCOL_VERSION: Int = 2

    const val ACTION_BIND_SERVICE: String =
        "com.brycewg.asrkb.imebridge.action.BIND_CLIPBOARD_SYNC_RUNTIME"
    const val DESCRIPTOR: String =
        "com.brycewg.asrkb.imebridge.ImeBridgeClipboardSyncService"

    const val TRANSACTION_ACTIVATE: Int = android.os.IBinder.FIRST_CALL_TRANSACTION + 0
    const val TRANSACTION_DEACTIVATE: Int = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
    /** 仅通知窗口已隐藏；同步会话和剪贴板订阅继续保持。 */
    const val TRANSACTION_WINDOW_HIDDEN: Int = android.os.IBinder.FIRST_CALL_TRANSACTION + 2

    const val RESULT_OK: Int = 1
    const val RESULT_PROTOCOL_MISMATCH: Int = -1
    const val RESULT_BAD_REQUEST: Int = -2
    const val RESULT_CALLER_REJECTED: Int = -3
    const val RESULT_IME_MISMATCH: Int = -4
    const val RESULT_HOST_REJECTED: Int = -5
    const val RESULT_SYNC_DISABLED: Int = -6
    const val RESULT_BRIDGE_UNAVAILABLE: Int = -7

    fun messageForCode(code: Int): String = when (code) {
        RESULT_OK -> "ok"
        RESULT_PROTOCOL_MISMATCH -> "protocol mismatch"
        RESULT_BAD_REQUEST -> "bad request"
        RESULT_CALLER_REJECTED -> "caller rejected"
        RESULT_IME_MISMATCH -> "ime mismatch"
        RESULT_HOST_REJECTED -> "host rejected"
        RESULT_SYNC_DISABLED -> "sync disabled"
        RESULT_BRIDGE_UNAVAILABLE -> "bridge unavailable"
        else -> "unknown: $code"
    }
}
