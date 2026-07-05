// Tests conservative primary ASR terminal error classification for backup failover.
package com.brycewg.asrkb.asr

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class AsrPrimaryErrorClassifierTest {
    @Test
    fun networkSetupFailuresAreImmediateFailover() {
        val immediateCases = listOf(
            AsrPrimaryErrorClassifier.classify(error = UnknownHostException("api.example.com")),
            AsrPrimaryErrorClassifier.classify(error = ConnectException("Connection refused")),
            AsrPrimaryErrorClassifier.classify(error = NoRouteToHostException("Network is unreachable")),
            AsrPrimaryErrorClassifier.classify(error = SSLHandshakeException("trust anchor not found"))
        )

        assertEquals(
            listOf(
                AsrPrimaryErrorStrategy.ImmediateFailover,
                AsrPrimaryErrorStrategy.ImmediateFailover,
                AsrPrimaryErrorStrategy.ImmediateFailover,
                AsrPrimaryErrorStrategy.ImmediateFailover
            ),
            immediateCases
        )
    }

    @Test
    fun httpClientAndServerFailuresAreImmediateFailover() {
        assertEquals(
            AsrPrimaryErrorStrategy.ImmediateFailover,
            AsrPrimaryErrorClassifier.classify(httpStatusCode = 401)
        )
        assertEquals(
            AsrPrimaryErrorStrategy.ImmediateFailover,
            AsrPrimaryErrorClassifier.classify(message = "Recognition request failed: HTTP 503 service unavailable")
        )
    }

    @Test
    fun httpSuccessAndRedirectCodesDoNotTriggerImmediateFailover() {
        assertEquals(
            AsrPrimaryErrorStrategy.WaitTimeout,
            AsrPrimaryErrorClassifier.classify(httpStatusCode = 204)
        )
        assertEquals(
            AsrPrimaryErrorStrategy.WaitTimeout,
            AsrPrimaryErrorClassifier.classify(message = "Recognition request failed: HTTP 302 redirect")
        )
    }

    @Test
    fun authenticationQuotaAndExplicitProviderErrorsAreImmediateFailover() {
        val immediateCases = listOf(
            "Authentication failed. Credentials invalid or expired.",
            "Free service quota exceeded, please try again later",
            "ASR Error 45000000: invalid resource id"
        )

        assertEquals(
            listOf(
                AsrPrimaryErrorStrategy.ImmediateFailover,
                AsrPrimaryErrorStrategy.ImmediateFailover,
                AsrPrimaryErrorStrategy.ImmediateFailover
            ),
            immediateCases.map { AsrPrimaryErrorClassifier.classify(message = it) }
        )
    }

    @Test
    fun genericProviderSuccessCodesDoNotTriggerImmediateFailover() {
        assertEquals(
            AsrPrimaryErrorStrategy.WaitTimeout,
            AsrPrimaryErrorClassifier.classify(message = "status: 200")
        )
        assertEquals(
            AsrPrimaryErrorStrategy.WaitTimeout,
            AsrPrimaryErrorClassifier.classify(message = "code: 200")
        )
    }

    @Test
    fun readTimeoutAndUnknownErrorsWaitForSwitchDeadline() {
        assertEquals(
            AsrPrimaryErrorStrategy.WaitTimeout,
            AsrPrimaryErrorClassifier.classify(error = SocketTimeoutException("Read timed out"))
        )
        assertEquals(
            AsrPrimaryErrorStrategy.WaitTimeout,
            AsrPrimaryErrorClassifier.classify(message = "Recognition timed out. Please try again.")
        )
        assertEquals(
            AsrPrimaryErrorStrategy.WaitTimeout,
            AsrPrimaryErrorClassifier.classify(message = "Something unexpected happened")
        )
    }
}
