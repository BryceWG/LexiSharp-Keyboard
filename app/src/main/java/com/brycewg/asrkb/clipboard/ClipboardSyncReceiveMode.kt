package com.brycewg.asrkb.clipboard

/**
 * SyncClipboard 自动接收方式：三选一，互斥。
 *
 * - [OFF]：不自动接收，生命周期允许时仍可本地复制上传
 * - [POLLING]：按间隔周期拉取
 * - [REALTIME]：SignalR 为首选远端接收机制
 */
enum class ClipboardSyncReceiveMode(val id: String) {
    OFF("off"),
    POLLING("polling"),
    REALTIME("realtime");

    companion object {
        fun fromId(id: String?): ClipboardSyncReceiveMode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OFF

        fun fromLegacy(realtime: Boolean, autoPull: Boolean): ClipboardSyncReceiveMode = when {
            realtime -> REALTIME
            autoPull -> POLLING
            else -> OFF
        }
    }
}
