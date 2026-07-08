/**
 * 输入法桥接 Push PCM 绑定服务入口。
 *
 * 归属模块：imebridge
 */
package com.brycewg.asrkb.imebridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.brycewg.asrkb.store.Prefs

class ImeBridgePcmSessionService : Service() {
    private val prefs by lazy { Prefs(this) }
    private val bridgeClient by lazy { ImeBridgeClient(this) }
    private val controller by lazy {
        ImeBridgePcmSessionController(
            featureGate = BridgePcmFeatureGate { prefs.imeBridgePcmRecordingEnabled },
            currentImePackageProvider = CurrentImePackageProvider {
                ImeBridgeClient.resolveCurrentImePackage(this)
            },
            bridgeStatusProvider = BridgeStatusProvider { bridgeClient.queryStatus() },
            sessionFactory = ImeBridgePcmExternalSessionFactory(this, prefs, bridgeClient),
            logSink = ApiLogBridgePcmSessionLogSink
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        controller.cancelActiveForShutdown()
        super.onDestroy()
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(ImeBridgePcmContract.DESCRIPTOR)
                    return true
                }
                ImeBridgePcmContract.TRANSACTION_BEGIN -> {
                    data.enforceInterface(ImeBridgePcmContract.DESCRIPTOR)
                    val sessionId = data.readString().orEmpty()
                    val result = controller.begin(
                        BridgePcmBeginRequest(
                            sessionId = sessionId,
                            callerPackages = resolveCallingPackages()
                        )
                    )
                    replyResult(reply, result)
                    return true
                }
                ImeBridgePcmContract.TRANSACTION_WRITE_FRAME -> {
                    data.enforceInterface(ImeBridgePcmContract.DESCRIPTOR)
                    val sessionId = data.readString().orEmpty()
                    val pcm = data.createByteArray() ?: ByteArray(0)
                    val sampleRate = data.readInt()
                    val channels = data.readInt()
                    replyResult(
                        reply,
                        controller.writeFrame(operationRequest(sessionId), pcm, sampleRate, channels)
                    )
                    return true
                }
                ImeBridgePcmContract.TRANSACTION_FINISH -> {
                    data.enforceInterface(ImeBridgePcmContract.DESCRIPTOR)
                    val sessionId = data.readString().orEmpty()
                    replyResult(reply, controller.finish(operationRequest(sessionId)))
                    return true
                }
                ImeBridgePcmContract.TRANSACTION_CANCEL -> {
                    data.enforceInterface(ImeBridgePcmContract.DESCRIPTOR)
                    val sessionId = data.readString().orEmpty()
                    replyResult(reply, controller.cancel(operationRequest(sessionId)))
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

    private fun operationRequest(sessionId: String): BridgePcmSessionOperationRequest =
        BridgePcmSessionOperationRequest(sessionId, resolveCallingPackages())

    private fun replyResult(reply: Parcel?, result: BridgePcmOperationResult) {
        reply?.apply {
            writeNoException()
            writeInt(result.code)
            writeString(result.message)
        }
    }
}
