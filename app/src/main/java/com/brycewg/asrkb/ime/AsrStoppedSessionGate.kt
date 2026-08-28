/**
 * 按 IME 录音会话去重 onStopped 终态交付。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

internal class AsrStoppedSessionGate {
    private val lock = Any()
    private var deliveredSessionSeq = 0L

    /**
     * 会话序号在会话结束时会被归零并由下一次录音复用，
     * 因此每次新会话开始必须重置，否则后续会话的 onStopped 会被误判成重复交付而丢弃。
     */
    fun reset() {
        synchronized(lock) { deliveredSessionSeq = 0L }
    }

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
