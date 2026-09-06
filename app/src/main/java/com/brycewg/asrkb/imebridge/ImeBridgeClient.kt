/**
 * 输入法桥接客户端：主应用通过有序广播请求被 Hook 的输入法提交文本。
 *
 * 归属模块：imebridge
 */
package com.brycewg.asrkb.imebridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.ApiLogStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ImeBridgeResult(
    val code: Int,
    val message: String,
    val targetPackage: String?,
    val hasInputConnection: Boolean,
    val isSensitiveField: Boolean,
    val isImeWindowVisible: Boolean,
    val moduleVersion: String? = null,
    val supportsInsertText: Boolean = false,
    val supportsComposingPreview: Boolean = false,
    val supportsFinishComposingText: Boolean = false,
    val supportsSessions: Boolean = false,
    val supportsPcmRecording: Boolean = false,
    val supportsClipboard: Boolean = false,
    val clipboardText: String? = null,
    val isClipboardSensitive: Boolean = false,
    val activeSessionId: String? = null,
    val lastOperation: String? = null,
    val lastResultCode: Int = 0,
    val lastError: String? = null
) {
    val isSuccess: Boolean get() = code == ImeBridgeContract.RESULT_OK
    val isBridgePresent: Boolean
        get() = code != ImeBridgeContract.RESULT_NO_RECEIVER &&
            code != ImeBridgeContract.RESULT_NO_CURRENT_IME &&
            code != ImeBridgeContract.RESULT_TIMEOUT &&
            code != ImeBridgeContract.RESULT_SEND_FAILED
}

class ImeBridgeClient(private val context: Context) {
    fun queryStatus(timeoutMs: Long = DEFAULT_STATUS_TIMEOUT_MS): ImeBridgeResult = sendBridgeRequest(
        ImeBridgeContract.ACTION_QUERY_STATUS,
        null,
        timeoutMs,
        warnOnFailure = false
    )

    fun beginSession(
        sessionId: String,
        timeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS
    ): ImeBridgeResult = sendBridgeRequest(
        ImeBridgeContract.ACTION_BEGIN_SESSION,
        null,
        timeoutMs,
        sessionId = sessionId,
        warnOnFailure = false
    )

    fun cancelSession(
        sessionId: String,
        timeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS
    ): ImeBridgeResult = sendBridgeRequest(
        ImeBridgeContract.ACTION_CANCEL_SESSION,
        null,
        timeoutMs,
        sessionId = sessionId,
        warnOnFailure = false
    )

    fun insertText(
        text: String,
        cursorPosition: Int = 1,
        timeoutMs: Long = DEFAULT_INSERT_TIMEOUT_MS,
        sessionId: String? = null
    ): ImeBridgeResult {
        if (text.isEmpty()) {
            return ImeBridgeResult(
                code = ImeBridgeContract.RESULT_BAD_REQUEST,
                message = "empty text",
                targetPackage = resolveCurrentImePackage(),
                hasInputConnection = false,
                isSensitiveField = false,
                isImeWindowVisible = false
            )
        }
        return sendBridgeRequest(
            ImeBridgeContract.ACTION_INSERT_TEXT,
            text,
            timeoutMs,
            cursorPosition,
            sessionId,
            warnOnFailure = true
        )
    }

    fun setComposingText(
        text: String,
        cursorPosition: Int = 1,
        timeoutMs: Long = DEFAULT_COMPOSING_TIMEOUT_MS,
        sessionId: String? = null
    ): ImeBridgeResult = sendBridgeRequest(
        ImeBridgeContract.ACTION_SET_COMPOSING_TEXT,
        text,
        timeoutMs,
        cursorPosition,
        sessionId,
        warnOnFailure = false
    )

    fun finishComposingText(
        timeoutMs: Long = DEFAULT_COMPOSING_TIMEOUT_MS,
        sessionId: String? = null
    ): ImeBridgeResult = sendBridgeRequest(
        ImeBridgeContract.ACTION_FINISH_COMPOSING_TEXT,
        null,
        timeoutMs,
        sessionId = sessionId,
        warnOnFailure = false
    )

    fun setClipboardText(
        text: String,
        timeoutMs: Long = DEFAULT_CLIPBOARD_TIMEOUT_MS
    ): ImeBridgeResult {
        if (text.isEmpty()) {
            return ImeBridgeResult(
                code = ImeBridgeContract.RESULT_BAD_REQUEST,
                message = "empty text",
                targetPackage = resolveCurrentImePackage(),
                hasInputConnection = false,
                isSensitiveField = false,
                isImeWindowVisible = false
            )
        }
        return sendBridgeRequest(
            ImeBridgeContract.ACTION_SET_CLIPBOARD_TEXT,
            text,
            timeoutMs,
            warnOnFailure = false
        )
    }

    fun getClipboardText(
        timeoutMs: Long = DEFAULT_CLIPBOARD_TIMEOUT_MS
    ): ImeBridgeResult = sendBridgeRequest(
        ImeBridgeContract.ACTION_GET_CLIPBOARD_TEXT,
        null,
        timeoutMs,
        warnOnFailure = false
    )

    fun startClipboardObserve(
        subscriptionToken: String,
        timeoutMs: Long = DEFAULT_CLIPBOARD_TIMEOUT_MS
    ): ImeBridgeResult = sendBridgeRequest(
        ImeBridgeContract.ACTION_START_CLIPBOARD_OBSERVE,
        null,
        timeoutMs,
        clipboardSubscriptionToken = subscriptionToken,
        warnOnFailure = false
    )

    fun stopClipboardObserve(
        timeoutMs: Long = DEFAULT_CLIPBOARD_TIMEOUT_MS
    ): ImeBridgeResult = sendBridgeRequest(
        ImeBridgeContract.ACTION_STOP_CLIPBOARD_OBSERVE,
        null,
        timeoutMs,
        warnOnFailure = false
    )

    fun resolveCurrentImePackage(): String? = resolveCurrentImePackage(context)

    private fun sendBridgeRequest(
        action: String,
        text: String?,
        timeoutMs: Long,
        cursorPosition: Int = 1,
        sessionId: String? = null,
        clipboardSubscriptionToken: String? = null,
        warnOnFailure: Boolean
    ): ImeBridgeResult {
        val targetPackage = resolveCurrentImePackage()
        val requestId = UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        if (targetPackage == null) {
            val noImeResult = ImeBridgeResult(
                code = ImeBridgeContract.RESULT_NO_CURRENT_IME,
                message = "no current ime",
                targetPackage = null,
                hasInputConnection = false,
                isSensitiveField = false,
                isImeWindowVisible = false
            )
            recordBridgeApiLog(action, requestId, null, text, cursorPosition, sessionId, started, noImeResult)
            return noImeResult
        }

        val latch = CountDownLatch(1)
        var result: ImeBridgeResult? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val extras = getResultExtras(false)
                result = ImeBridgeResult(
                    code = resultCode,
                    message = extras?.getString(ImeBridgeContract.EXTRA_MESSAGE)
                        ?: resultData
                        ?: messageForCode(resultCode),
                    targetPackage = extras?.getString(ImeBridgeContract.EXTRA_TARGET_PACKAGE) ?: targetPackage,
                    hasInputConnection = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_HAS_INPUT_CONNECTION,
                        false
                    ) == true,
                    isSensitiveField = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_IS_SENSITIVE_FIELD,
                        false
                    ) == true,
                    isImeWindowVisible = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_IME_WINDOW_VISIBLE,
                        false
                    ) == true,
                    moduleVersion = extras?.getString(ImeBridgeContract.EXTRA_MODULE_VERSION),
                    supportsInsertText = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_SUPPORTS_INSERT_TEXT,
                        resultCode == ImeBridgeContract.RESULT_OK
                    ) == true,
                    supportsComposingPreview = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_SUPPORTS_COMPOSING_PREVIEW,
                        false
                    ) == true,
                    supportsFinishComposingText = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_SUPPORTS_FINISH_COMPOSING_TEXT,
                        false
                    ) == true,
                    supportsSessions = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_SUPPORTS_SESSIONS,
                        false
                    ) == true,
                    supportsPcmRecording = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_SUPPORTS_PCM_RECORDING,
                        false
                    ) == true,
                    supportsClipboard = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_SUPPORTS_CLIPBOARD,
                        false
                    ) == true,
                    clipboardText = extras?.getString(ImeBridgeContract.EXTRA_TEXT),
                    isClipboardSensitive = extras?.getBoolean(
                        ImeBridgeContract.EXTRA_IS_CLIPBOARD_SENSITIVE,
                        false
                    ) == true,
                    activeSessionId = extras?.getString(ImeBridgeContract.EXTRA_ACTIVE_SESSION_ID),
                    lastOperation = extras?.getString(ImeBridgeContract.EXTRA_LAST_OPERATION),
                    lastResultCode = extras?.getInt(ImeBridgeContract.EXTRA_LAST_RESULT_CODE, 0) ?: 0,
                    lastError = extras?.getString(ImeBridgeContract.EXTRA_LAST_ERROR)
                )
                latch.countDown()
            }
        }

        val intent = Intent(action).apply {
            setPackage(targetPackage)
            putExtra(ImeBridgeContract.EXTRA_PROTOCOL_VERSION, ImeBridgeContract.PROTOCOL_VERSION)
            putExtra(ImeBridgeContract.EXTRA_REQUEST_ID, requestId)
            putExtra(ImeBridgeContract.EXTRA_CURSOR_POSITION, cursorPosition)
            if (!sessionId.isNullOrEmpty()) putExtra(ImeBridgeContract.EXTRA_SESSION_ID, sessionId)
            if (!clipboardSubscriptionToken.isNullOrEmpty()) {
                putExtra(
                    ImeBridgeContract.EXTRA_CLIPBOARD_SUBSCRIPTION_TOKEN,
                    clipboardSubscriptionToken
                )
            }
            if (text != null) putExtra(ImeBridgeContract.EXTRA_TEXT, text)
        }

        try {
            context.applicationContext.sendOrderedBroadcast(
                intent,
                null,
                receiver,
                BridgeResultHandler.handler,
                ImeBridgeContract.RESULT_NO_RECEIVER,
                "no receiver",
                null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "sendBridgeRequest failed: action=$action target=$targetPackage", t)
            val failedResult = ImeBridgeResult(
                code = ImeBridgeContract.RESULT_SEND_FAILED,
                message = t.message ?: "send failed",
                targetPackage = targetPackage,
                hasInputConnection = false,
                isSensitiveField = false,
                isImeWindowVisible = false
            )
            recordBridgeApiLog(action, requestId, targetPackage, text, cursorPosition, sessionId, started, failedResult)
            ImeBridgeWarningToast.show(context, failedResult.code, warnOnFailure)
            return failedResult
        }

        val received = try {
            latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "waiting bridge result failed", t)
            false
        }

        val finalResult = if (received) {
            result ?: ImeBridgeResult(
                code = ImeBridgeContract.RESULT_NO_RECEIVER,
                message = "no receiver",
                targetPackage = targetPackage,
                hasInputConnection = false,
                isSensitiveField = false,
                isImeWindowVisible = false
            )
        } else {
            ImeBridgeResult(
                code = ImeBridgeContract.RESULT_TIMEOUT,
                message = "timeout",
                targetPackage = targetPackage,
                hasInputConnection = false,
                isSensitiveField = false,
                isImeWindowVisible = false
            )
        }
        recordBridgeApiLog(action, requestId, targetPackage, text, cursorPosition, sessionId, started, finalResult)
        ImeBridgeWarningToast.show(context, finalResult.code, warnOnFailure)
        return finalResult
    }

    private fun recordBridgeApiLog(
        action: String,
        requestId: String,
        targetPackage: String?,
        text: String?,
        cursorPosition: Int,
        sessionId: String?,
        startedElapsedMs: Long,
        result: ImeBridgeResult
    ) {
        if (!shouldRecordApiLog(action, result)) return
        val durationMs = (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L)
        val shortAction = shortActionName(action)
        ApiLogStore.add(
            ApiLogStore.ApiLogRecord(
                category = "IME_BRIDGE",
                vendor = result.targetPackage ?: targetPackage.orEmpty(),
                model = result.moduleVersion.orEmpty(),
                source = "floating",
                protocol = "Broadcast",
                method = shortAction,
                host = targetPackage.orEmpty(),
                path = "/$shortAction",
                requestSummary = buildString {
                    append("requestId=").append(requestId.take(8))
                    append("; textChars=").append(text?.length ?: 0)
                    append("; cursorPosition=").append(cursorPosition)
                    if (!sessionId.isNullOrEmpty()) append("; session=").append(sessionId.take(8))
                },
                requestStructure = "action=$action",
                responseSummary = buildString {
                    append("code=").append(result.code)
                    append("; message=").append(result.message)
                    append("; hasInputConnection=").append(result.hasInputConnection)
                    append("; imeWindowVisible=").append(result.isImeWindowVisible)
                    append("; sensitive=").append(result.isSensitiveField)
                    append("; supportsInsert=").append(result.supportsInsertText)
                    append("; supportsPreview=").append(result.supportsComposingPreview)
                    append("; supportsSessions=").append(result.supportsSessions)
                    append("; supportsPcmRecording=").append(result.supportsPcmRecording)
                    append("; supportsClipboard=").append(result.supportsClipboard)
                },
                success = result.isSuccess,
                durationMs = durationMs,
                errorSummary = if (result.isSuccess) "" else result.message.take(240)
            )
        )
    }

    private fun shouldRecordApiLog(action: String, result: ImeBridgeResult): Boolean {
        if (action == ImeBridgeContract.ACTION_SET_COMPOSING_TEXT && result.isSuccess) return false
        if (action == ImeBridgeContract.ACTION_FINISH_COMPOSING_TEXT && result.isSuccess) return false
        if (action == ImeBridgeContract.ACTION_GET_CLIPBOARD_TEXT && result.isSuccess) return false
        return true
    }

    private fun shortActionName(action: String): String = action.substringAfterLast('.').lowercase()

    companion object {
        private const val TAG = "ImeBridgeClient"
        private const val DEFAULT_STATUS_TIMEOUT_MS = 350L
        private const val DEFAULT_INSERT_TIMEOUT_MS = 700L
        private const val DEFAULT_COMPOSING_TIMEOUT_MS = 120L
        private const val DEFAULT_SESSION_TIMEOUT_MS = 180L
        private const val DEFAULT_CLIPBOARD_TIMEOUT_MS = 500L

        fun resolveCurrentImePackage(context: Context): String? {
            val raw = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                )
            } catch (t: Throwable) {
                Log.w(TAG, "resolveCurrentImePackage failed", t)
                null
            } ?: return null

            ComponentName.unflattenFromString(raw)?.packageName?.let { return it }
            return raw.substringBefore('/').takeIf { it.isNotBlank() }
        }

        fun messageForCode(code: Int): String = when (code) {
            ImeBridgeContract.RESULT_OK -> "ok"
            ImeBridgeContract.RESULT_NO_RECEIVER -> "no receiver"
            ImeBridgeContract.RESULT_PROTOCOL_MISMATCH -> "protocol mismatch"
            ImeBridgeContract.RESULT_NO_ACTIVE_IME -> "no active ime"
            ImeBridgeContract.RESULT_NO_INPUT_CONNECTION -> "no input connection"
            ImeBridgeContract.RESULT_SENSITIVE_FIELD -> "sensitive field"
            ImeBridgeContract.RESULT_COMMIT_FAILED -> "commit failed"
            ImeBridgeContract.RESULT_BAD_REQUEST -> "bad request"
            ImeBridgeContract.RESULT_COMPOSING_FAILED -> "composing failed"
            ImeBridgeContract.RESULT_SESSION_MISMATCH -> "session mismatch"
            ImeBridgeContract.RESULT_CLIPBOARD_FAILED -> "clipboard failed"
            ImeBridgeContract.RESULT_NO_CURRENT_IME -> "no current ime"
            ImeBridgeContract.RESULT_TIMEOUT -> "timeout"
            ImeBridgeContract.RESULT_SEND_FAILED -> "send failed"
            else -> "unknown: $code"
        }
    }
}

private object BridgeResultHandler {
    val handler: Handler by lazy {
        val thread = HandlerThread("ImeBridgeResult")
        thread.start()
        Handler(thread.looper)
    }
}

internal fun imeBridgeWarningMessageRes(code: Int, warnOnFailure: Boolean): Int? {
    if (!warnOnFailure) return null
    return when (code) {
        ImeBridgeContract.RESULT_PROTOCOL_MISMATCH -> R.string.toast_ime_bridge_protocol_mismatch
        ImeBridgeContract.RESULT_NO_RECEIVER,
        ImeBridgeContract.RESULT_TIMEOUT,
        ImeBridgeContract.RESULT_SEND_FAILED -> R.string.toast_ime_bridge_connection_failed
        else -> null
    }
}

internal object ImeBridgeWarningToast {
    private const val COOLDOWN_MS = 30_000L
    private val lastShownElapsedMs = ConcurrentHashMap<Int, AtomicLong>()

    fun show(context: Context, code: Int, warnOnFailure: Boolean) {
        val messageRes = imeBridgeWarningMessageRes(code, warnOnFailure) ?: return
        if (!tryAcquire(messageRes, SystemClock.elapsedRealtime())) return
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context.applicationContext,
                messageRes,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    internal fun tryAcquire(messageRes: Int, nowElapsedMs: Long): Boolean {
        val lastShown = lastShownElapsedMs.computeIfAbsent(messageRes) {
            AtomicLong(-COOLDOWN_MS)
        }
        while (true) {
            val previous = lastShown.get()
            if (nowElapsedMs - previous < COOLDOWN_MS) return false
            if (lastShown.compareAndSet(previous, nowElapsedMs)) return true
        }
    }

    internal fun wasShownWithin(messageRes: Int, nowElapsedMs: Long, windowMs: Long): Boolean {
        val lastShown = lastShownElapsedMs[messageRes]?.get() ?: return false
        return nowElapsedMs - lastShown < windowMs
    }
}
