/**
 * OkHttp 实现的 SyncClipboard SignalR JSON 协议客户端。
 *
 * 流程：negotiate → WebSocket → handshake → 监听 RemoteProfileChanged。
 * 不把凭证写入日志；断开由调用方决定是否重试。
 */
package com.brycewg.asrkb.clipboard

import android.util.Base64
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

internal class SyncClipboardSignalRClient(
    private val serverBase: String,
    private val username: String,
    private val password: String,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = defaultClient()
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(error: Throwable?)
        fun onRemoteProfileChanged(profileJson: String?)
    }
    companion object {
        private const val TAG = "ScSignalR"
        private const val RECORD_SEPARATOR = '\u001e'
        private const val PING_INTERVAL_MS = 15_000L
        private val EMPTY_JSON = "{}".toRequestBody("application/json".toMediaType())
        private val VERSION_PATTERN = Regex("\\d+(?:\\.\\d+)+(?:[-+][0-9A-Za-z.-]+)?")

        fun hubUrlFromServerBase(serverBase: String): String? {
            val root = serverRoot(serverBase) ?: return null
            return "$root/SyncClipboardHub"
        }

        internal fun probeServerVersion(
            serverBase: String,
            username: String,
            password: String,
            httpClient: OkHttpClient = defaultClient().newBuilder()
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        ): Boolean? {
            val root = serverRoot(serverBase) ?: return null
            return try {
                val request = Request.Builder()
                    .url("$root/api/version")
                    .header("Authorization", basicAuthHeader(username, password))
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> supportsRealtimeVersion(
                            response.body.string().trim().trim('"')
                        )
                        response.code == 404 || response.code == 405 -> false
                        else -> null
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接
            .writeTimeout(12, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        private fun serverRoot(serverBase: String): String? {
            val raw = serverBase.trim().trimEnd('/')
            if (raw.isBlank()) return null
            return raw.removeSuffix("/SyncClipboard.json").trimEnd('/').ifBlank { null }
        }

        private fun supportsRealtimeVersion(version: String): Boolean? {
            if (!VERSION_PATTERN.matches(version)) return null
            val parts = version.substringBefore('-').substringBefore('+')
                .split('.').take(3).map { it.toIntOrNull() ?: return false }
            val normalized = parts + List(3 - parts.size) { 0 }
            return normalized[0] > 3 ||
                normalized[0] == 3 && normalized[1] > 1 ||
                normalized[0] == 3 && normalized[1] == 1 && normalized[2] >= 1
        }

        private fun basicAuthHeader(username: String, password: String): String {
            val token = Base64.encodeToString(
                "$username:$password".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            return "Basic $token"
        }
    }

    @Volatile private var listener: Listener? = null
    @Volatile private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val lifecycleLock = Any()
    @Volatile private var generation = 0L
    private var negotiateCall: Call? = null
    private var pingJob: Job? = null

    val isConnected: Boolean get() = connected.get()

    fun start(listener: Listener) {
        stop()
        val currentGeneration = synchronized(lifecycleLock) {
            this.listener = listener
            started.set(true)
            ++generation
        }
        scope.launch(Dispatchers.IO) {
            try {
                connectInternal(currentGeneration)
            } catch (t: Throwable) {
                if (isCurrent(currentGeneration)) {
                    Log.w(TAG, "SignalR connect failed", t)
                    markDisconnected(currentGeneration, t)
                }
            }
        }
    }

    fun stop() {
        val (call, socket) = synchronized(lifecycleLock) {
            generation++
            started.set(false)
            connected.set(false)
            pingJob?.cancel()
            pingJob = null
            val activeCall = negotiateCall
            negotiateCall = null
            val activeSocket = webSocket
            webSocket = null
            listener = null
            activeCall to activeSocket
        }
        call?.cancel()
        try {
            socket?.close(1000, "stop")
        } catch (t: Throwable) {
            Log.w(TAG, "WebSocket close failed", t)
        }
    }

    private fun connectInternal(currentGeneration: Long) {
        val hubUrl = hubUrlFromServerBase(serverBase)
            ?: throw IllegalArgumentException("invalid server base")
        val auth = basicAuthHeader(username, password)
        val token = negotiate(hubUrl, auth, currentGeneration)
        if (!isCurrent(currentGeneration)) return
        val wsUrl = toWebSocketUrl(hubUrl, token)
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", auth)
            .build()
        val receiveBuffer = StringBuilder()
        val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(currentGeneration)) {
                    webSocket.close(1000, "stale")
                    return
                }
                // SignalR handshake
                webSocket.send("""{"protocol":"json","version":1}$RECORD_SEPARATOR""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(currentGeneration, receiveBuffer, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleText(currentGeneration, receiveBuffer, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                markDisconnected(currentGeneration, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                markDisconnected(currentGeneration, t)
            }
        })
        val accepted = synchronized(lifecycleLock) {
            if (isCurrent(currentGeneration)) {
                webSocket = socket
                true
            } else {
                false
            }
        }
        if (!accepted) socket.close(1000, "stale")
    }

    private fun negotiate(hubUrl: String, auth: String, currentGeneration: Long): String {
        val negotiateUrl = "$hubUrl/negotiate?negotiateVersion=1"
        val req = Request.Builder()
            .url(negotiateUrl)
            .header("Authorization", auth)
            .post(EMPTY_JSON)
            .build()
        val call = httpClient.newCall(req)
        synchronized(lifecycleLock) {
            if (!isCurrent(currentGeneration)) throw CancellationException("stale connection")
            negotiateCall = call
        }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 404 || resp.code == 405 || resp.code == 501) {
                        throw RealtimeUnavailableException(resp.code)
                    }
                    throw IllegalStateException("negotiate HTTP ${resp.code}")
                }
                val body = resp.body.string()
                val obj = JSONObject(body)
                val token = obj.optString("connectionToken")
                    .ifBlank { obj.optString("connectionId") }
                if (token.isBlank()) {
                    throw IllegalStateException("negotiate missing connection token")
                }
                return token
            }
        } finally {
            synchronized(lifecycleLock) {
                if (negotiateCall === call) negotiateCall = null
            }
        }
    }

    private fun toWebSocketUrl(hubUrl: String, connectionToken: String): String {
        val wsBase = when {
            hubUrl.startsWith("https://", ignoreCase = true) ->
                "wss://" + hubUrl.removePrefix("https://").removePrefix("HTTPS://")
            hubUrl.startsWith("http://", ignoreCase = true) ->
                "ws://" + hubUrl.removePrefix("http://").removePrefix("HTTP://")
            else -> hubUrl
        }
        val sep = if (wsBase.contains('?')) '&' else '?'
        return "$wsBase${sep}id=${java.net.URLEncoder.encode(connectionToken, Charsets.UTF_8.name())}"
    }

    private fun handleText(
        currentGeneration: Long,
        receiveBuffer: StringBuilder,
        chunk: String
    ) {
        if (!isCurrent(currentGeneration)) return
        receiveBuffer.append(chunk)
        while (true) {
            val idx = receiveBuffer.indexOf(RECORD_SEPARATOR.toString())
            if (idx < 0) break
            val frame = receiveBuffer.substring(0, idx)
            receiveBuffer.delete(0, idx + 1)
            if (frame.isBlank()) continue
            dispatchFrame(currentGeneration, frame)
        }
    }

    private fun dispatchFrame(currentGeneration: Long, frame: String) {
        if (!isCurrent(currentGeneration)) return
        val obj = try {
            JSONObject(frame)
        } catch (t: Throwable) {
            Log.w(TAG, "Invalid SignalR frame", t)
            return
        }
        // Handshake ack: {}
        if (!obj.has("type")) {
            if (obj.has("error")) {
                markDisconnected(
                    currentGeneration,
                    IllegalStateException(obj.optString("error"))
                )
                return
            }
            val connectedListener = synchronized(lifecycleLock) {
                if (isCurrent(currentGeneration) && connected.compareAndSet(false, true)) {
                    startPingLoop(currentGeneration)
                    listener
                } else {
                    null
                }
            }
            connectedListener?.onConnected()
            return
        }
        when (obj.optInt("type", -1)) {
            1 -> { // Invocation
                val target = obj.optString("target")
                if (target.equals("RemoteProfileChanged", ignoreCase = true)) {
                    val profileJson = (obj.optJSONArray("arguments")?.opt(0) as? JSONObject)
                        ?.toString()
                    val currentListener = synchronized(lifecycleLock) {
                        listener.takeIf { isCurrent(currentGeneration) }
                    }
                    currentListener?.onRemoteProfileChanged(profileJson)
                }
                // RemoteHistoryChanged 本 PRD 不扩展历史推送写入
            }
            6 -> {
                // Ping：回一个 ping 保持协议活跃
                webSocket?.send("""{"type":6}$RECORD_SEPARATOR""")
            }
            7 -> { // Close
                val error = obj.optString("error").takeIf { it.isNotBlank() }
                markDisconnected(
                    currentGeneration,
                    error?.let { IllegalStateException(it) },
                    closeSocket = true
                )
            }
        }
    }

    private fun startPingLoop(currentGeneration: Long) {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && isCurrent(currentGeneration) && connected.get()) {
                delay(PING_INTERVAL_MS)
                try {
                    webSocket?.send("""{"type":6}$RECORD_SEPARATOR""")
                } catch (t: Throwable) {
                    Log.w(TAG, "SignalR ping failed", t)
                    markDisconnected(currentGeneration, t)
                    break
                }
            }
        }
    }

    private fun markDisconnected(
        currentGeneration: Long,
        error: Throwable?,
        closeSocket: Boolean = false
    ) {
        var currentListener: Listener? = null
        var socketToClose: WebSocket? = null
        val shouldNotify = synchronized(lifecycleLock) {
            if (!isCurrent(currentGeneration) || !started.compareAndSet(true, false)) {
                false
            } else {
                currentListener = listener
                if (closeSocket) {
                    socketToClose = webSocket
                    webSocket = null
                }
                connected.set(false)
                pingJob?.cancel()
                pingJob = null
                true
            }
        }
        if (!shouldNotify) return
        socketToClose?.let { socket ->
            try {
                if (!socket.close(1000, "server close")) socket.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "WebSocket close failed", t)
                socket.cancel()
            }
        }
        currentListener?.onDisconnected(error)
    }

    private fun isCurrent(currentGeneration: Long): Boolean =
        started.get() && generation == currentGeneration

}

internal class RealtimeUnavailableException(httpCode: Int) :
    IllegalStateException("SignalR negotiate unsupported: HTTP $httpCode")
