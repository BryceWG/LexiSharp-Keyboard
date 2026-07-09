package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolcAuthHeadersTest {
    @Test
    fun oldAuthUsesAppKeyAndAccessKeyHeaders() {
        val headers = volcHeaders(
            auth = VolcAuthValues(
                useNewAuth = false,
                apiKey = "ignored",
                appKey = "app-key",
                accessKey = "access-key"
            ),
            resourceId = "resource",
            requestId = "request-id",
            sequence = "-1"
        )

        assertEquals("app-key", headers["X-Api-App-Key"])
        assertEquals("access-key", headers["X-Api-Access-Key"])
        assertNull(headers["X-Api-Key"])
        assertEquals("resource", headers["X-Api-Resource-Id"])
        assertEquals("request-id", headers["X-Api-Request-Id"])
        assertEquals("-1", headers["X-Api-Sequence"])
    }

    @Test
    fun newAuthUsesOnlyApiKeyHeader() {
        val headers = volcHeaders(
            auth = VolcAuthValues(
                useNewAuth = true,
                apiKey = "api-key",
                appKey = "ignored",
                accessKey = "ignored"
            ),
            resourceId = "resource",
            connectId = "connect-id"
        )

        assertEquals("api-key", headers["X-Api-Key"])
        assertNull(headers["X-Api-App-Key"])
        assertNull(headers["X-Api-Access-Key"])
        assertEquals("resource", headers["X-Api-Resource-Id"])
        assertEquals("connect-id", headers["X-Api-Connect-Id"])
    }
}
