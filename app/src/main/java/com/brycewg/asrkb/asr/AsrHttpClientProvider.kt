/**
 * 为在线文件 ASR 请求提供共享连接池与调度器的 OkHttp builder。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.debug.DebugLogManager
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient

internal object AsrHttpClientProvider {
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .eventListenerFactory { ConnectionReuseEventListener() }
            .build()
    }

    fun newBuilder(): OkHttpClient.Builder = sharedClient.newBuilder()

    private class ConnectionReuseEventListener : EventListener() {
        private var connectionStarted = false

        override fun callStart(call: Call) {
            AsrConnectionWarmer.observeRequest(call.request())
        }

        override fun connectStart(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy
        ) {
            connectionStarted = true
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            DebugLogManager.log(
                category = "asr",
                event = "asr_http_connection_acquired",
                data = mapOf(
                    "reused" to !connectionStarted,
                    "protocol" to connection.protocol().toString()
                )
            )
        }
    }
}
