/**
 * Hook 总开关关闭时，幂等终止活跃的 PCM / Clipboard Bridge session。
 *
 * 归属模块：imebridge
 */
package com.brycewg.asrkb.imebridge

internal object ImeBridgeRuntimeShutdown {
    fun onMasterEnabledChanged(enabled: Boolean) {
        if (enabled) return
        shutdownActiveSessions()
    }

    fun shutdownActiveSessions() {
        ImeBridgePcmSessionService.cancelActiveForShutdownIfPresent()
        ImeBridgeClipboardSyncService.finishActiveForShutdownIfPresent()
    }
}
