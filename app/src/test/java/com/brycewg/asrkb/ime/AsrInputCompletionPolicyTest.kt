/**
 * 语音输入完成后是否通知固化的判定测试。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrInputCompletionPolicyTest {
    @Test
    fun notifiesWhenCommittedTextIsReadyAndSessionStopped() {
        assertTrue(
            AsrInputCompletionPolicy.shouldNotifyInputSolidified(
                committedText = "你好",
                sessionStillRunning = false
            )
        )
    }

    @Test
    fun skipsEmptyCommittedText() {
        assertFalse(
            AsrInputCompletionPolicy.shouldNotifyInputSolidified(
                committedText = "",
                sessionStillRunning = false
            )
        )
    }

    @Test
    fun skipsWhenSessionStillRunning() {
        assertFalse(
            AsrInputCompletionPolicy.shouldNotifyInputSolidified(
                committedText = "你好",
                sessionStillRunning = true
            )
        )
    }
}
