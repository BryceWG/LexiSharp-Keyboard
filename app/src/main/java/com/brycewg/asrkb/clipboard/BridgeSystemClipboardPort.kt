/**
 * 经 IME Bridge 在第三方输入法进程内读写系统剪贴板。
 *
 * 归属模块：clipboard / imebridge
 */
package com.brycewg.asrkb.clipboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.brycewg.asrkb.imebridge.ImeBridgeClient
import com.brycewg.asrkb.imebridge.ImeBridgeContract
import com.brycewg.asrkb.imebridge.ImeBridgeResult
import java.util.UUID

internal interface ClipboardBridgeClient {
    fun getClipboardText(): ImeBridgeResult
    fun setClipboardText(text: String): ImeBridgeResult
    fun startClipboardObserve(subscriptionToken: String): ImeBridgeResult
    fun stopClipboardObserve(): ImeBridgeResult
}

internal class ImeBridgeClipboardClientAdapter(
    private val client: ImeBridgeClient
) : ClipboardBridgeClient {
    override fun getClipboardText(): ImeBridgeResult = client.getClipboardText()
    override fun setClipboardText(text: String): ImeBridgeResult = client.setClipboardText(text)
    override fun startClipboardObserve(subscriptionToken: String): ImeBridgeResult =
        client.startClipboardObserve(subscriptionToken)
    override fun stopClipboardObserve(): ImeBridgeResult = client.stopClipboardObserve()
}

internal class BridgeSystemClipboardPort(
    context: Context,
    private val bridgeClient: ClipboardBridgeClient =
        ImeBridgeClipboardClientAdapter(ImeBridgeClient(context)),
    private val expectedTargetPackage: String? = ImeBridgeClient.resolveCurrentImePackage(context),
    private val subscriptionToken: String = UUID.randomUUID().toString()
) : SystemClipboardPort {
    private val appContext = context.applicationContext

    @Volatile private var observer: (() -> Unit)? = null
    private var changeReceiver: BroadcastReceiver? = null

    override val actor: SystemClipboardActor = SystemClipboardActor.BRIDGE

    override fun readText(): ClipboardTextRead? {
        val result = try {
            bridgeClient.getClipboardText()
        } catch (e: Throwable) {
            Log.e(TAG, "Bridge getClipboardText failed", e)
            return null
        }
        if (!result.isSuccess) {
            Log.d(TAG, "Bridge getClipboardText code=${result.code} chars=${result.clipboardText?.length ?: 0}")
            return null
        }
        val text = result.clipboardText?.takeIf { it.isNotEmpty() } ?: return null
        return ClipboardTextRead(text = text, isSensitive = result.isClipboardSensitive)
    }

    override fun writeText(text: String): Boolean {
        val result = try {
            bridgeClient.setClipboardText(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Bridge setClipboardText failed", e)
            return false
        }
        if (result.isSuccess) {
            return true
        }
        Log.w(TAG, "Bridge setClipboardText failed: code=${result.code} msg=${result.message}")
        return false
    }

    override fun startObserving(onChanged: () -> Unit) {
        observer = onChanged
        registerChangeReceiver()
        try {
            val result = bridgeClient.startClipboardObserve(subscriptionToken)
            if (!result.isSuccess) {
                Log.w(TAG, "Bridge startClipboardObserve failed: code=${result.code}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Bridge startClipboardObserve threw", e)
        }
    }

    override fun stopObserving() {
        observer = null
        try {
            bridgeClient.stopClipboardObserve()
        } catch (e: Throwable) {
            Log.e(TAG, "Bridge stopClipboardObserve threw", e)
        }
        unregisterChangeReceiver()
    }

    private fun registerChangeReceiver() {
        if (changeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ImeBridgeContract.ACTION_CLIPBOARD_TEXT_CHANGED) return
                if (!isTrustedClipboardChange(
                        protocol = intent.getIntExtra(ImeBridgeContract.EXTRA_PROTOCOL_VERSION, 0),
                        expectedTargetPackage = expectedTargetPackage,
                        actualTargetPackage = intent.getStringExtra(ImeBridgeContract.EXTRA_TARGET_PACKAGE),
                        expectedSubscriptionToken = subscriptionToken,
                        actualSubscriptionToken = intent.getStringExtra(
                            ImeBridgeContract.EXTRA_CLIPBOARD_SUBSCRIPTION_TOKEN
                        )
                    )
                ) return
                observer?.invoke()
            }
        }
        changeReceiver = receiver
        val filter = IntentFilter(ImeBridgeContract.ACTION_CLIPBOARD_TEXT_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(
                    receiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register clipboard change receiver", e)
            changeReceiver = null
        }
    }

    private fun unregisterChangeReceiver() {
        val receiver = changeReceiver ?: return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to unregister clipboard change receiver", e)
        }
        changeReceiver = null
    }

    companion object {
        private const val TAG = "BridgeClipboardPort"
    }
}

internal fun isTrustedClipboardChange(
    protocol: Int,
    expectedTargetPackage: String?,
    actualTargetPackage: String?,
    expectedSubscriptionToken: String,
    actualSubscriptionToken: String?
): Boolean =
    protocol == ImeBridgeContract.PROTOCOL_VERSION &&
        !expectedTargetPackage.isNullOrEmpty() &&
        actualTargetPackage == expectedTargetPackage &&
        actualSubscriptionToken == expectedSubscriptionToken
