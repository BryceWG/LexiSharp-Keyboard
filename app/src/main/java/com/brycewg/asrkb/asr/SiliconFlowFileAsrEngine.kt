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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 使用 SiliconFlow "audio/transcriptions" API 的非流式 ASR 引擎。
 * 行为：start() 开始录制 PCM；stop() 完成并上传一个请求；仅调用 onFinal。
 */
class SiliconFlowFileAsrEngine(
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
        running.set(false)
    }

    private fun startRecordThenRecognize() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError("录音权限未授予")
                running.set(false)
                return@launch
            }

            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
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
                listener.onError("无法初始化录音: ${t.message}")
                running.set(false)
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError("录音初始化失败")
                running.set(false)
                return@launch
            }

            val pcmBuffer = ByteArrayOutputStream()
            try {
                recorder.startRecording()
                val buf = ByteArray(chunkBytes)
                val maxBytes = 5 * 60 * sampleRate * 2
                while (true) {
                    if (!running.get()) break
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        pcmBuffer.write(buf, 0, read)
                        if (pcmBuffer.size() >= maxBytes) break
                    }
                }
            } catch (t: Throwable) {
                listener.onError("录音错误: ${t.message}")
            } finally {
                try { recorder.stop() } catch (_: Throwable) {}
                try { recorder.release() } catch (_: Throwable) {}
            }

            val pcmBytes = pcmBuffer.toByteArray()
            if (pcmBytes.isEmpty()) {
                listener.onError("空音频")
                return@launch
            }

            try {
                val wav = pcmToWav(pcmBytes)
                // SiliconFlow 需要包含文件和模型的 multipart/form-data
                // 将临时 wav 文件写入缓存目录以供 OkHttp 5 使用 FileBody
                val tmp = File.createTempFile("asr_", ".wav", context.cacheDir)
                FileOutputStream(tmp).use { it.write(wav) }

                val apiKey = prefs.sfApiKey
                if (apiKey.isBlank()) {
                    listener.onError("未配置 SiliconFlow API Key")
                    return@launch
                }
                val model = prefs.sfModel
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", model)
                    .addFormDataPart(
                        "file",
                        "audio.wav",
                        tmp.asRequestBody("audio/wav".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url(Prefs.SF_ENDPOINT)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(multipart)
                    .build()

                val t0 = System.nanoTime()
                val resp = http.newCall(request).execute()
                resp.use { r ->
                    if (!r.isSuccessful) {
                        val msg = r.message
                        listener.onError("识别请求失败: HTTP ${r.code}${if (msg.isBlank()) "" else ": $msg"}")
                        return@launch
                    }
                    val bodyStr = r.body?.string() ?: ""
                    val text = try {
                        val obj = JSONObject(bodyStr)
                        obj.optString("text", "")
                    } catch (_: Throwable) { "" }
                    if (text.isNotBlank()) {
                        val dt = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                        try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
                        listener.onFinal(text)
                    } else {
                        listener.onError("识别返回为空")
                    }
                }
            } catch (t: Throwable) {
                listener.onError("识别失败: ${t.message}")
            }
        }
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val headerSize = 44
        val dataSize = pcm.size
        val totalDataLen = dataSize + 36
        val out = ByteArrayOutputStream(headerSize + dataSize)
        out.write("RIFF".toByteArray())
        out.write(intToBytesLE(totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToBytesLE(16))
        out.write(shortToBytesLE(1))
        out.write(shortToBytesLE(channels))
        out.write(intToBytesLE(sampleRate))
        out.write(intToBytesLE(byteRate))
        out.write(shortToBytesLE((channels * bitsPerSample / 8)))
        out.write(shortToBytesLE(bitsPerSample))
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
}
