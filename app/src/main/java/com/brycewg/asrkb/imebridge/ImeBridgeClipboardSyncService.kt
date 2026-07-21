/**
 * Bridge Clipboard Sync Runtime 绑定服务：插件仅提交激活信号与目标 IME，不含凭证。
 *
 * 归属模块：imebridge / clipboard
 */
package com.brycewg.asrkb.imebridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.brycewg.asrkb.clipboard.ClipboardSyncRuntimeService
import com.brycewg.asrkb.store.Prefs

class ImeBridgeClipboardSyncService : Service() {
    private val prefs by lazy { Prefs(this) }
    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeTargetPackage: String? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // 插件进程死亡或主动解绑：诚实结束 Bridge session
        finishActiveSession(actorDied = true)
        return false
    }

    override fun onDestroy() {
        finishActiveSession(actorDied = true)
        super.onDestroy()
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(ImeBridgeClipboardSyncContract.DESCRIPTOR)
                    return true
                }
                ImeBridgeClipboardSyncContract.TRANSACTION_ACTIVATE -> {
                    data.enforceInterface(ImeBridgeClipboardSyncContract.DESCRIPTOR)
                    val protocolVersion = data.readInt()
                    val sessionId = data.readString().orEmpty()
                    val targetImePackage = data.readString().orEmpty()
                    val result = activateBridge(
                        protocolVersion,
                        sessionId,
                        targetImePackage,
                        resolveCallingPackages()
                    )
                    replyResult(reply, result)
                    return true
                }
                ImeBridgeClipboardSyncContract.TRANSACTION_WINDOW_HIDDEN -> {
                    data.enforceInterface(ImeBridgeClipboardSyncContract.DESCRIPTOR)
                    val sessionId = data.readString().orEmpty()
                    val result = if (ownsSession(sessionId, resolveCallingPackages())) {
                        ImeBridgeClipboardSyncContract.RESULT_OK
                    } else {
                        ImeBridgeClipboardSyncContract.RESULT_BAD_REQUEST
                    }
                    replyResult(reply, result)
                    return true
                }
                ImeBridgeClipboardSyncContract.TRANSACTION_DEACTIVATE -> {
                    data.enforceInterface(ImeBridgeClipboardSyncContract.DESCRIPTOR)
                    val sessionId = data.readString().orEmpty()
                    val result = if (ownsSession(sessionId, resolveCallingPackages())) {
                        finishActiveSession(actorDied = false)
                        ImeBridgeClipboardSyncContract.RESULT_OK
                    } else {
                        ImeBridgeClipboardSyncContract.RESULT_BAD_REQUEST
                    }
                    replyResult(reply, result)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun resolveCallingPackages(): Set<String> {
        val uid = Binder.getCallingUid()
        return packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
    }

    @Synchronized
    private fun ownsSession(sessionId: String, callerPackages: Set<String>): Boolean =
        activeSessionId == sessionId && activeTargetPackage in callerPackages

    @Synchronized
    private fun finishActiveSession(actorDied: Boolean) {
        val sessionId = activeSessionId ?: return
        activeSessionId = null
        activeTargetPackage = null
        if (actorDied) {
            ClipboardSyncRuntimeService.onBridgeActorDied(this, sessionId)
        } else {
            ClipboardSyncRuntimeService.deactivateBridge(this, sessionId)
        }
    }

    @Synchronized
    private fun activateBridge(
        protocolVersion: Int,
        sessionId: String,
        targetImePackage: String,
        callerPackages: Set<String>
    ): Int {
        val result = validateBridgeActivation(
            protocolVersion,
            sessionId,
            targetImePackage,
            callerPackages,
            ImeBridgeClient.resolveCurrentImePackage(this),
            prefs.syncClipboardEnabled,
            prefs.floatingImeBridgeEnabled
        )
        if (result == ImeBridgeClipboardSyncContract.RESULT_OK) {
            activeSessionId = sessionId
            activeTargetPackage = targetImePackage
            ClipboardSyncRuntimeService.activateBridge(this, targetImePackage, sessionId)
        }
        return result
    }

    private fun replyResult(reply: Parcel?, result: Int) {
        reply?.apply {
            writeNoException()
            writeInt(result)
            writeString(ImeBridgeClipboardSyncContract.messageForCode(result))
        }
    }
}

internal fun validateBridgeActivation(
    protocolVersion: Int,
    sessionId: String,
    targetImePackage: String,
    callerPackages: Set<String>,
    currentImePackage: String?,
    syncEnabled: Boolean,
    bridgeEnabled: Boolean
): Int = when {
    protocolVersion != 1 -> ImeBridgeClipboardSyncContract.RESULT_PROTOCOL_MISMATCH
    sessionId.isBlank() || targetImePackage.isBlank() ->
        ImeBridgeClipboardSyncContract.RESULT_BAD_REQUEST
    !syncEnabled -> ImeBridgeClipboardSyncContract.RESULT_SYNC_DISABLED
    !bridgeEnabled -> ImeBridgeClipboardSyncContract.RESULT_BRIDGE_UNAVAILABLE
    currentImePackage != targetImePackage -> ImeBridgeClipboardSyncContract.RESULT_IME_MISMATCH
    targetImePackage !in callerPackages -> ImeBridgeClipboardSyncContract.RESULT_CALLER_REJECTED
    else -> ImeBridgeClipboardSyncContract.RESULT_OK
}
