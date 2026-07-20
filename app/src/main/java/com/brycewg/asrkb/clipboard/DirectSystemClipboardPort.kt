/**
 * 本进程直接操作 ClipboardManager 的 Clipboard Port 实现。
 *
 * 归属模块：clipboard
 */
package com.brycewg.asrkb.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log

class DirectSystemClipboardPort(
    context: Context
) : SystemClipboardPort {
    private val clipboard =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Volatile private var observer: (() -> Unit)? = null
    @Volatile private var registered = false
    @Volatile private var suppressOwnChange = false

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (suppressOwnChange) {
            suppressOwnChange = false
            return@OnPrimaryClipChangedListener
        }
        observer?.invoke()
    }

    override val actor: SystemClipboardActor = SystemClipboardActor.DIRECT

    override fun readText(): ClipboardTextRead? {
        val clip = try {
            clipboard.primaryClip
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read clipboard", e)
            null
        } ?: return null
        val text = try {
            readClipboardText(clip)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read clipboard text", e)
            null
        }?.takeIf { it.isNotEmpty() } ?: return null
        return ClipboardTextRead(text = text, isSensitive = isClipSensitive(clip))
    }

    override fun writeText(text: String): Boolean {
        val clip = ClipData.newPlainText("SyncClipboard", text)
        suppressOwnChange = true
        return try {
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write clipboard text", e)
            suppressOwnChange = false
            false
        } finally {
            // 与历史行为一致：若回调未同步触发，也清除抑制位
            suppressOwnChange = false
        }
    }

    override fun startObserving(onChanged: () -> Unit) {
        observer = onChanged
        if (!registered) {
            try {
                clipboard.addPrimaryClipChangedListener(clipListener)
                registered = true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to add clipboard listener", e)
            }
        }
    }

    override fun stopObserving() {
        observer = null
        if (registered) {
            try {
                clipboard.removePrimaryClipChangedListener(clipListener)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to remove clipboard listener", e)
            }
            registered = false
        }
        suppressOwnChange = false
    }

    private fun isClipSensitive(clip: ClipData): Boolean {
        val description = clip.description ?: return false
        return try {
            val extras: PersistableBundle? = description.extras
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            } else {
                extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read clipboard sensitivity extras", e)
            false
        }
    }

    companion object {
        private const val TAG = "DirectClipboardPort"
    }
}
