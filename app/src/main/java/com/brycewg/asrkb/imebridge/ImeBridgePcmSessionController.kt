/**
 * 输入法桥接 Push PCM 会话安全 gate 与状态机。
 *
 * 归属模块：imebridge
 */
package com.brycewg.asrkb.imebridge

internal data class BridgePcmBeginRequest(
    val sessionId: String,
    val callerPackages: Set<String>
)

internal data class BridgePcmSessionConfig(
    val sessionId: String,
    val supportsComposingPreview: Boolean
)

internal data class BridgePcmSessionOperationRequest(
    val sessionId: String,
    val callerPackages: Set<String>
)

internal data class BridgePcmOperationResult(
    val code: Int,
    val message: String = ImeBridgePcmContract.messageForCode(code),
    val requestAudioFocus: Boolean = false
) {
    val isSuccess: Boolean get() = code == ImeBridgePcmContract.RESULT_OK
}

internal fun interface BridgePcmFeatureGate {
    fun isEnabled(): Boolean
}

internal fun interface CurrentImePackageProvider {
    fun currentImePackage(): String?
}

internal fun interface BridgeStatusProvider {
    fun queryStatus(): ImeBridgeResult
}

internal fun interface BridgePcmAudioFocusPolicy {
    fun shouldRequestAudioFocus(): Boolean
}

internal fun interface BridgePcmSessionFactory {
    fun create(config: BridgePcmSessionConfig, onEnded: (String) -> Unit): BridgePcmSession?

    fun close() = Unit
}

internal interface BridgePcmSession {
    fun start()
    fun writeFrame(pcm: ByteArray, sampleRate: Int, channels: Int)
    fun finish()
    fun cancel()
}

internal class ImeBridgePcmSessionController(
    private val featureGate: BridgePcmFeatureGate,
    private val currentImePackageProvider: CurrentImePackageProvider,
    private val bridgeStatusProvider: BridgeStatusProvider,
    private val sessionFactory: BridgePcmSessionFactory,
    private val audioFocusPolicy: BridgePcmAudioFocusPolicy = BridgePcmAudioFocusPolicy { false },
    private val logSink: BridgePcmSessionLogSink = NoopBridgePcmSessionLogSink,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private var active: ActiveSession? = null
    private var closed = false
    private var closeWhenEnded = false
    private var factoryClosed = false

    @Synchronized
    fun begin(request: BridgePcmBeginRequest): BridgePcmOperationResult {
        if (closed) return result(ImeBridgePcmContract.RESULT_SESSION_UNAVAILABLE, "service closed")
        if (!featureGate.isEnabled()) return result(ImeBridgePcmContract.RESULT_FEATURE_DISABLED)
        if (!isValidSessionId(request.sessionId)) {
            return result(ImeBridgePcmContract.RESULT_BAD_REQUEST, "invalid session id")
        }

        val currentImePackage = currentImePackageProvider.currentImePackage()
            ?: return result(ImeBridgePcmContract.RESULT_PACKAGE_MISMATCH, "no current ime")
        if (!request.callerPackages.contains(currentImePackage)) {
            return result(ImeBridgePcmContract.RESULT_PACKAGE_MISMATCH)
        }
        if (active != null) return result(ImeBridgePcmContract.RESULT_BUSY)

        val status = bridgeStatusProvider.queryStatus()
        val statusPackage = status.targetPackage
        if (!status.isSuccess || !status.isBridgePresent || !status.supportsPcmRecording) {
            return result(ImeBridgePcmContract.RESULT_BRIDGE_UNAVAILABLE)
        }
        if (!status.supportsInsertText || !status.supportsSessions) {
            return result(ImeBridgePcmContract.RESULT_BRIDGE_UNAVAILABLE)
        }
        if (!statusPackage.isNullOrBlank() && statusPackage != currentImePackage) {
            return result(ImeBridgePcmContract.RESULT_PACKAGE_MISMATCH)
        }
        if (!status.hasInputConnection) {
            return result(ImeBridgePcmContract.RESULT_NO_INPUT_CONNECTION)
        }
        if (!status.isImeWindowVisible) {
            return result(ImeBridgePcmContract.RESULT_BRIDGE_UNAVAILABLE, "ime window hidden")
        }
        if (status.isSensitiveField) {
            return result(ImeBridgePcmContract.RESULT_SENSITIVE_FIELD)
        }

        val session = sessionFactory.create(
            BridgePcmSessionConfig(
                sessionId = request.sessionId,
                supportsComposingPreview = status.supportsComposingPreview
            ),
            ::onSessionEnded
        )
            ?: return result(ImeBridgePcmContract.RESULT_SESSION_UNAVAILABLE)
        active = ActiveSession(
            sessionId = request.sessionId,
            ownerPackage = currentImePackage,
            state = ActiveState.Recording,
            session = session,
            summary = BridgePcmSessionSummary(
                targetPackage = currentImePackage,
                sessionId = request.sessionId,
                startedMs = clockMs(),
                hasInputConnection = status.hasInputConnection,
                isSensitiveField = status.isSensitiveField,
                isImeWindowVisible = status.isImeWindowVisible,
                supportsComposingPreview = status.supportsComposingPreview,
                supportsPcmRecording = status.supportsPcmRecording
            )
        )
        return try {
            session.start()
            result(
                code = ImeBridgePcmContract.RESULT_OK,
                requestAudioFocus = audioFocusPolicy.shouldRequestAudioFocus()
            ).also {
                active?.let { current -> record(current, "begin", it) }
            }
        } catch (t: Throwable) {
            val failedSummary = active?.summary
            active = null
            runCatching { session.cancel() }
            result(
                ImeBridgePcmContract.RESULT_SESSION_UNAVAILABLE,
                t.message ?: ImeBridgePcmContract.messageForCode(ImeBridgePcmContract.RESULT_SESSION_UNAVAILABLE)
            ).also { failedSummary?.let { summary -> record(summary, "begin", it) } }
        }
    }

    @Synchronized
    fun writeFrame(
        sessionId: String,
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int
    ): BridgePcmOperationResult = writeFrame(BridgePcmSessionOperationRequest(sessionId, emptySet()), pcm, sampleRate, channels)

    @Synchronized
    fun writeFrame(
        request: BridgePcmSessionOperationRequest,
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int
    ): BridgePcmOperationResult {
        val current = activeRecording(request) ?: return stale()
        if (pcm.isEmpty()) return result(ImeBridgePcmContract.RESULT_OK)
        if (!isValidPcmFormat(sampleRate, channels)) {
            return result(ImeBridgePcmContract.RESULT_BAD_REQUEST, "invalid pcm format")
        }
        current.session.writeFrame(pcm, sampleRate, channels)
        current.summary.addFrame(pcm.size, sampleRate, channels)
        return result(ImeBridgePcmContract.RESULT_OK)
    }

    @Synchronized
    fun finish(sessionId: String): BridgePcmOperationResult = finish(BridgePcmSessionOperationRequest(sessionId, emptySet()))

    @Synchronized
    fun finish(request: BridgePcmSessionOperationRequest): BridgePcmOperationResult {
        val current = activeRecording(request) ?: return stale()
        active = current.copy(state = ActiveState.Finishing)
        return try {
            current.session.finish()
            result(ImeBridgePcmContract.RESULT_OK).also {
                record(current, "finish", it)
            }
        } catch (t: Throwable) {
            active = null
            runCatching { current.session.cancel() }
            result(
                ImeBridgePcmContract.RESULT_UNSUPPORTED,
                t.message ?: ImeBridgePcmContract.messageForCode(ImeBridgePcmContract.RESULT_UNSUPPORTED)
            ).also { record(current, "finish", it) }
        }
    }

    @Synchronized
    fun cancel(sessionId: String): BridgePcmOperationResult = cancel(BridgePcmSessionOperationRequest(sessionId, emptySet()))

    @Synchronized
    fun cancel(request: BridgePcmSessionOperationRequest): BridgePcmOperationResult {
        val current = active
        if (current == null || current.sessionId != request.sessionId || !isOwnerCaller(current, request)) {
            return stale()
        }
        if (current.state != ActiveState.Recording) return stale()
        active = null
        return try {
            current.session.cancel()
            result(ImeBridgePcmContract.RESULT_OK).also {
                record(current, "cancel", it)
            }
        } catch (t: Throwable) {
            result(
                ImeBridgePcmContract.RESULT_UNSUPPORTED,
                t.message ?: ImeBridgePcmContract.messageForCode(ImeBridgePcmContract.RESULT_UNSUPPORTED)
            ).also { record(current, "cancel", it) }
        }
    }

    @Synchronized
    fun cancelActiveForShutdown() {
        val current = active ?: return
        // Binder 客户端在提交完 PCM 后会解除绑定。此时服务可能立即销毁，但
        // Finishing 会话仍在等待 ASR final/error/timeout，不能把正常解绑当成取消。
        if (current.state == ActiveState.Finishing) return
        active = null
        runCatching { current.session.cancel() }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        cancelActiveForShutdown()
        if (active?.state == ActiveState.Finishing) {
            closeWhenEnded = true
        } else {
            closeFactory()
        }
    }

    @Synchronized
    private fun onSessionEnded(sessionId: String) {
        val current = active ?: return
        if (current.sessionId != sessionId) return
        active = null
        if (closeWhenEnded) closeFactory()
    }

    private fun closeFactory() {
        if (factoryClosed) return
        factoryClosed = true
        sessionFactory.close()
    }

    private fun activeRecording(request: BridgePcmSessionOperationRequest): ActiveSession? {
        val current = active ?: return null
        if (current.sessionId != request.sessionId || current.state != ActiveState.Recording) return null
        if (!isOwnerCaller(current, request)) return null
        return current
    }

    private fun isOwnerCaller(
        current: ActiveSession,
        request: BridgePcmSessionOperationRequest
    ): Boolean {
        if (request.callerPackages.isEmpty()) return true
        val currentImePackage = currentImePackageProvider.currentImePackage() ?: return false
        return currentImePackage == current.ownerPackage &&
            request.callerPackages.contains(current.ownerPackage)
    }

    private fun stale(): BridgePcmOperationResult = result(ImeBridgePcmContract.RESULT_STALE_SESSION)

    private fun result(
        code: Int,
        message: String = ImeBridgePcmContract.messageForCode(code),
        requestAudioFocus: Boolean = false
    ) = BridgePcmOperationResult(code, message, requestAudioFocus)

    private fun record(
        current: ActiveSession,
        operation: String,
        result: BridgePcmOperationResult
    ) {
        record(current.summary, operation, result)
    }

    private fun record(
        summary: BridgePcmSessionSummary,
        operation: String,
        result: BridgePcmOperationResult
    ) {
        logSink.record(summary.record(operation, result, clockMs()))
    }

    private data class ActiveSession(
        val sessionId: String,
        val ownerPackage: String,
        val state: ActiveState,
        val session: BridgePcmSession,
        val summary: BridgePcmSessionSummary
    )

    private enum class ActiveState {
        Recording,
        Finishing
    }

    private companion object {
        private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        private const val MIN_SAMPLE_RATE = 8_000
        private const val MAX_SAMPLE_RATE = 192_000
        private const val PCM_MONO_CHANNELS = 1

        fun isValidSessionId(sessionId: String): Boolean = sessionId.isNotBlank() && SESSION_ID_PATTERN.matches(sessionId)

        fun isValidPcmFormat(sampleRate: Int, channels: Int): Boolean = sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE && channels == PCM_MONO_CHANNELS
    }
}
