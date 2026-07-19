/**
 * 按 IME 录音会话去重 onStopped 终态交付。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

internal class AsrStoppedSessionGate {
    private val lock = Any()
    private var deliveredSessionSeq = 0L

    fun tryDeliver(sessionSeq: Long): Boolean {
        if (sessionSeq == 0L) return false
        return synchronized(lock) {
            if (deliveredSessionSeq == sessionSeq) {
                false
            } else {
                deliveredSessionSeq = sessionSeq
                true
            }
        }
    }
}
