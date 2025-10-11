package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64
import com.brycewg.asrkb.R

/**
 * 使用火山引擎"recognize/flash" API的非流式ASR引擎。
 * 行为：start()开始录制PCM；stop()完成并上传一个请求；仅调用onFinal。
 */
class VolcFileAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val onRequestDuration: ((Long) -> Unit)? = null
) : StreamingAsrEngine {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private var running = AtomicBoolean(false)
    private var audioJob: Job? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        running.set(true)
        startRecordThenRecognize()
    }

    override fun stop() {
        // 翻转运行标志；任务将退出其循环并继续上传
        running.set(false)
    }

    private fun startRecordThenRecognize() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            // 权限检查
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return@launch
            }

            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            // 每个块读取约200ms的数据
            val chunkBytes = ((sampleRate / 5) * 2)
            val bufferSize = maxOf(minBuffer, chunkBytes)
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (t: Throwable) {
                listener.onError(context.getString(R.string.error_audio_init_cannot, t.message ?: ""))
                running.set(false)
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError(context.getString(R.string.error_audio_init_failed))
                running.set(false)
                return@launch
            }

            val pcmBuffer = ByteArrayOutputStream()
            try {
                recorder.startRecording()
                val buf = ByteArray(chunkBytes)
                // 软限制以避免在极长录音时占用过多内存（最大约5分钟）
                val maxBytes = 5 * 60 * sampleRate * 2
                while (true) {
                    if (!running.get()) break
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        pcmBuffer.write(buf, 0, read)
                        if (pcmBuffer.size() >= maxBytes) {
                            // 超过限制时自动停止
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
            } finally {
                try { recorder.stop() } catch (_: Throwable) {}
                try { recorder.release() } catch (_: Throwable) {}
            }

            // 如果有数据，准备音频并上传
            val pcmBytes = pcmBuffer.toByteArray()
            if (pcmBytes.isEmpty()) {
                listener.onError(context.getString(R.string.error_audio_empty))
                return@launch
            }

            try {
                val wav = pcmToWav(pcmBytes)
                val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
                val json = buildRequestJson(b64)
                val reqBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                // 使用固定默认值进行文件识别以简化设置
                val resourceId = DEFAULT_FILE_RESOURCE
                val endpointUrl = Prefs.DEFAULT_ENDPOINT
                val request = Request.Builder()
                    .url(endpointUrl)
                    .addHeader("X-Api-App-Key", prefs.appKey)
                    .addHeader("X-Api-Access-Key", prefs.accessKey)
                    .addHeader("X-Api-Resource-Id", resourceId)
                    .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
                    .addHeader("X-Api-Sequence", "-1")
                    .post(reqBody)
                    .build()
                val t0 = System.nanoTime()
                val resp = http.newCall(request).execute()
                resp.use { r ->
                    if (!r.isSuccessful) {
                        val msg = r.header("X-Api-Message") ?: r.message
                        val suffix = if (msg.isBlank()) "" else ": $msg"
                        listener.onError(context.getString(R.string.error_request_failed_http, r.code, suffix))
                        return@launch
                    }
                    val bodyStr = r.body?.string() ?: ""
                    val text = try {
                        val obj = JSONObject(bodyStr)
                        if (obj.has("result")) {
                            obj.getJSONObject("result").optString("text", "")
                        } else ""
                    } catch (_: Throwable) { "" }
                    if (text.isNotBlank()) {
                        val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                        try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
                        listener.onFinal(text)
                    } else {
                        listener.onError(context.getString(R.string.error_asr_empty_result))
                    }
                }
            } catch (t: Throwable) {
                listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
            }
        }
    }

    private fun buildRequestJson(base64Audio: String): String {
        val user = JSONObject().apply {
            put("uid", prefs.appKey)
        }
        val audio = JSONObject().apply {
            put("data", base64Audio)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
        }
        val root = JSONObject().apply {
            put("user", user)
            put("audio", audio)
            put("request", request)
        }
        return root.toString()
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val headerSize = 44
        val dataSize = pcm.size
        val totalDataLen = dataSize + 36
        val out = ByteArrayOutputStream(headerSize + dataSize)
        // RIFF文件头
        out.write("RIFF".toByteArray())
        out.write(intToBytesLE(totalDataLen))
        out.write("WAVE".toByteArray())
        // fmt子块
        out.write("fmt ".toByteArray())
        out.write(intToBytesLE(16)) // PCM的Subchunk1Size
        out.write(shortToBytesLE(1)) // AudioFormat = 1 (PCM)
        out.write(shortToBytesLE(channels))
        out.write(intToBytesLE(sampleRate))
        out.write(intToBytesLE(byteRate))
        out.write(shortToBytesLE((channels * bitsPerSample / 8))) // BlockAlign
        out.write(shortToBytesLE(bitsPerSample))
        // data子块
        out.write("data".toByteArray())
        out.write(intToBytesLE(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(v)
        return bb.array()
    }

    private fun shortToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(v.toShort())
        return bb.array()
    }

    companion object {
        private const val DEFAULT_FILE_RESOURCE = "volc.bigasr.auc_turbo"
    }


}
