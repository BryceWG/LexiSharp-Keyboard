package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.brycewg.asrkb.LocaleHelper

internal class UserNotifier(
    private val context: Context,
    private val handler: Handler,
    private val tag: String,
) {
    private var currentToast: Toast? = null

    fun cancel() {
        try {
            currentToast?.cancel()
        } catch (e: Throwable) {
            Log.w(tag, "Failed to cancel toast", e)
        } finally {
            currentToast = null
        }
    }

    fun showToast(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showToastInternal(message)
            return
        }
        try {
            handler.post { showToastInternal(message) }
        } catch (e: Throwable) {
            Log.e(tag, "Failed to post toast: $message", e)
        }
    }

    private fun showToastInternal(message: String) {
        try {
            currentToast?.cancel()
            val toastContext = try {
                LocaleHelper.wrap(context)
            } catch (e: Throwable) {
                Log.w(tag, "Failed to wrap context for toast", e)
                context
            }
            currentToast = Toast.makeText(toastContext, message, Toast.LENGTH_SHORT)
            currentToast?.show()
        } catch (e: Throwable) {
            Log.e(tag, "Failed to show toast: $message", e)
        }
    }
}
