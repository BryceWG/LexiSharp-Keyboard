package com.brycewg.asrkb.clipboard

import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.imebridge.ImeBridgeContract
import com.brycewg.asrkb.imebridge.ImeBridgeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgeSystemClipboardPortTest {
    @Test
    fun failedBridgeRead_doesNotReturnStaleCachedText() {
        val client = FakeClipboardBridgeClient()
        val port = BridgeSystemClipboardPort(
            context = ApplicationProvider.getApplicationContext(),
            bridgeClient = client,
            expectedTargetPackage = "com.example.ime",
            subscriptionToken = "trusted-token"
        )

        client.readResult = successResult(clipboardText = "old")
        assertEquals("old", port.readText()?.text)

        client.readResult = failedResult()
        assertNull(port.readText())
    }

    @Test
    fun clipboardChangeRequiresMatchingProtocolTargetAndSubscriptionToken() {
        assertTrue(
            isTrustedClipboardChange(
                protocol = ImeBridgeContract.PROTOCOL_VERSION,
                expectedTargetPackage = "com.example.ime",
                actualTargetPackage = "com.example.ime",
                expectedSubscriptionToken = "trusted-token",
                actualSubscriptionToken = "trusted-token"
            )
        )
        assertFalse(
            isTrustedClipboardChange(
                protocol = ImeBridgeContract.PROTOCOL_VERSION,
                expectedTargetPackage = "com.example.ime",
                actualTargetPackage = "com.example.ime",
                expectedSubscriptionToken = "trusted-token",
                actualSubscriptionToken = "spoofed-token"
            )
        )
    }
}

private class FakeClipboardBridgeClient : ClipboardBridgeClient {
    var readResult: ImeBridgeResult = failedResult()

    override fun getClipboardText(): ImeBridgeResult = readResult
    override fun setClipboardText(text: String): ImeBridgeResult = successResult()
    override fun startClipboardObserve(subscriptionToken: String): ImeBridgeResult = successResult()
    override fun stopClipboardObserve(): ImeBridgeResult = successResult()
}

private fun successResult(clipboardText: String? = null) = ImeBridgeResult(
    code = ImeBridgeContract.RESULT_OK,
    message = "ok",
    targetPackage = "com.example.ime",
    hasInputConnection = false,
    isSensitiveField = false,
    isImeWindowVisible = false,
    supportsClipboard = true,
    clipboardText = clipboardText
)

private fun failedResult() = ImeBridgeResult(
    code = ImeBridgeContract.RESULT_TIMEOUT,
    message = "timeout",
    targetPackage = "com.example.ime",
    hasInputConnection = false,
    isSensitiveField = false,
    isImeWindowVisible = false
)
