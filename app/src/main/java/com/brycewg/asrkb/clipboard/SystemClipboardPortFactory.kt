/**
 * 按当前默认 IME / 桥接能力选择 Clipboard Port。
 *
 * 归属模块：clipboard
 */
package com.brycewg.asrkb.clipboard

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.imebridge.ImeBridgeClient
import com.brycewg.asrkb.imebridge.ImeBridgeResult
import com.brycewg.asrkb.store.Prefs

object SystemClipboardPortFactory {
    private const val TAG = "ClipboardPortFactory"

    /**
     * - 当前默认 IME 是说点啥本体 → Direct
     * - 开启输入法桥接且模块声明 supportsClipboard → Bridge
     * - 否则 → Direct（主键盘可见等场景的既有尽力路径）
     */
    fun create(
        context: Context,
        prefs: Prefs,
        bridgeClient: ImeBridgeClient = ImeBridgeClient(context)
    ): SystemClipboardPort {
        val selfPackage = context.packageName
        val currentIme = try {
            ImeBridgeClient.resolveCurrentImePackage(context)
        } catch (e: Throwable) {
            Log.w(TAG, "resolveCurrentImePackage failed", e)
            null
        }
        val bridgeEnabled = try {
            prefs.floatingImeBridgeEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "read floatingImeBridgeEnabled failed", e)
            false
        }

        val status = if (currentIme != null && currentIme != selfPackage && bridgeEnabled) {
            try {
                bridgeClient.queryStatus()
            } catch (e: Throwable) {
                Log.w(TAG, "queryStatus for clipboard capability failed", e)
                null
            }
        } else {
            null
        }

        val actor = resolveClipboardActor(
            selfPackage = selfPackage,
            currentImePackage = currentIme,
            bridgeEnabled = bridgeEnabled,
            bridgeStatus = status
        )
        if (actor == SystemClipboardActor.DIRECT && status != null) {
            Log.d(
                TAG,
                "Bridge clipboard unavailable: code=${status.code} supports=${status.supportsClipboard}"
            )
        }

        return when (actor) {
            SystemClipboardActor.BRIDGE -> BridgeSystemClipboardPort(
                context = context,
                bridgeClient = ImeBridgeClipboardClientAdapter(bridgeClient),
                expectedTargetPackage = currentIme
            )
            SystemClipboardActor.DIRECT,
            SystemClipboardActor.UNAVAILABLE -> DirectSystemClipboardPort(context)
        }
    }

    /** 报告后台自动同步当前真正可用的执行者；不把尽力 Direct 回退伪装成可用。 */
    internal fun detectAvailableActor(
        context: Context,
        prefs: Prefs,
        bridgeClient: ImeBridgeClient = ImeBridgeClient(context)
    ): SystemClipboardActor {
        val currentIme = ImeBridgeClient.resolveCurrentImePackage(context)
            ?: return SystemClipboardActor.UNAVAILABLE
        if (currentIme == context.packageName) return SystemClipboardActor.DIRECT
        if (!prefs.floatingImeBridgeEnabled) return SystemClipboardActor.UNAVAILABLE
        val status = try {
            bridgeClient.queryStatus()
        } catch (e: Throwable) {
            Log.w(TAG, "queryStatus for clipboard availability failed", e)
            return SystemClipboardActor.UNAVAILABLE
        }
        return if (status.isSuccess && status.supportsClipboard) {
            SystemClipboardActor.BRIDGE
        } else {
            SystemClipboardActor.UNAVAILABLE
        }
    }
}

/**
 * 纯决策函数：与 [SystemClipboardPortFactory] 选择逻辑保持一致，便于单测。
 */
internal fun resolveClipboardActor(
    selfPackage: String,
    currentImePackage: String?,
    bridgeEnabled: Boolean,
    bridgeStatus: ImeBridgeResult?
): SystemClipboardActor {
    if (currentImePackage == null || currentImePackage == selfPackage) {
        return SystemClipboardActor.DIRECT
    }
    if (!bridgeEnabled) return SystemClipboardActor.DIRECT
    val status = bridgeStatus ?: return SystemClipboardActor.DIRECT
    if (!status.isSuccess || !status.supportsClipboard) return SystemClipboardActor.DIRECT
    return SystemClipboardActor.BRIDGE
}
