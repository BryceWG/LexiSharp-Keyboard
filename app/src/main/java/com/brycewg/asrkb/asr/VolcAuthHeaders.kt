/**
 * 火山引擎 ASR 鉴权请求头组装。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.Prefs
import okhttp3.Headers
import okhttp3.Request

internal data class VolcAuthValues(
    val useNewAuth: Boolean,
    val apiKey: String,
    val appKey: String,
    val accessKey: String
) {
    fun credentialHeaders(): List<Pair<String, String>> = if (useNewAuth) {
        listOf("X-Api-Key" to apiKey)
    } else {
        listOf(
            "X-Api-App-Key" to appKey,
            "X-Api-Access-Key" to accessKey
        )
    }
}

internal fun Prefs.volcAuthValues(): VolcAuthValues = VolcAuthValues(
    useNewAuth = volcUseNewAuth,
    apiKey = volcApiKey,
    appKey = appKey,
    accessKey = accessKey
)

internal fun volcHeaders(
    auth: VolcAuthValues,
    resourceId: String,
    connectId: String? = null,
    requestId: String? = null,
    sequence: String? = null
): Headers {
    val builder = Headers.Builder()
    auth.credentialHeaders().forEach { (name, value) ->
        builder.add(name, value)
    }
    builder.add("X-Api-Resource-Id", resourceId)
    connectId?.let { builder.add("X-Api-Connect-Id", it) }
    requestId?.let { builder.add("X-Api-Request-Id", it) }
    sequence?.let { builder.add("X-Api-Sequence", it) }
    return builder.build()
}

internal fun Request.Builder.volcHeaders(
    prefs: Prefs,
    resourceId: String,
    requestId: String,
    sequence: String = "-1"
): Request.Builder = headers(
    volcHeaders(
        auth = prefs.volcAuthValues(),
        resourceId = resourceId,
        requestId = requestId,
        sequence = sequence
    )
)
