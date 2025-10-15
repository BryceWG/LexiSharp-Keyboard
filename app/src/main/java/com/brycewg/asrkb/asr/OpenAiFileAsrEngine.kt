package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
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
 * 使用 OpenAI /v1/audio/transcriptions 的非流式 ASR 引擎。
 * 支持自定义 endpoint、API Key 与模型名。
 */
class OpenAiFileAsrEngine(
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
        listener.onError(context.getString(R.string.error_record_permission_denied))
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
        // 最大约10分钟
        val maxBytes = 10 * 60 * sampleRate * 2
        val silence = if (prefs.autoStopOnSilenceEnabled)
          SilenceDetector(sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
        else null
        while (true) {
          if (!running.get()) break
          val read = recorder.read(buf, 0, buf.size)
          if (read > 0) {
            pcmBuffer.write(buf, 0, read)
            if (silence?.shouldStop(buf, read) == true) {
              running.set(false)
              try { listener.onStopped() } catch (_: Throwable) {}
              break
            }
            if (pcmBuffer.size() >= maxBytes) break
          }
        }
      } catch (t: Throwable) {
        listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
      } finally {
        try { recorder.stop() } catch (_: Throwable) {}
        try { recorder.release() } catch (_: Throwable) {}
      }

      val pcmBytes = pcmBuffer.toByteArray()
      if (pcmBytes.isEmpty()) {
        listener.onError(context.getString(R.string.error_audio_empty))
        return@launch
      }

      try {
        val wav = pcmToWav(pcmBytes)
        val tmp = File.createTempFile("asr_oa_", ".wav", context.cacheDir)
        FileOutputStream(tmp).use { it.write(wav) }

        val apiKey = prefs.oaAsrApiKey
        val endpoint = prefs.oaAsrEndpoint.ifBlank { Prefs.DEFAULT_OA_ASR_ENDPOINT }
        val model = prefs.oaAsrModel.ifBlank { Prefs.DEFAULT_OA_ASR_MODEL }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
          .addFormDataPart("model", model)
          .addFormDataPart(
            "file",
            "audio.wav",
            tmp.asRequestBody("audio/wav".toMediaType())
          )
          // 可选：明确要求 json 响应（默认也是 json）
          .addFormDataPart("response_format", "json")
          .build()

        val reqBuilder = Request.Builder()
          .url(endpoint)
          .post(multipart)
          
        if (apiKey.isNotBlank()) {
          reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        val request = reqBuilder.build()

        val t0 = System.nanoTime()
        val resp = http.newCall(request).execute()
        resp.use { r ->
          val bodyStr = r.body?.string().orEmpty()
          if (!r.isSuccessful) {
            val extra = extractErrorHint(bodyStr)
            val msg = r.message
            val detail = formatHttpDetail(msg, extra)
            listener.onError(
              context.getString(R.string.error_request_failed_http, r.code, detail)
            )
            return@use
          }
          val text = parseTextFromResponse(bodyStr)
          if (text.isNotBlank()) {
            val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
            listener.onFinal(text)
          } else {
            listener.onError(context.getString(R.string.error_asr_empty_result))
          }
        }
      } catch (t: Throwable) {
        listener.onError(
          context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
        )
      }
    }
  }

  private fun extractErrorHint(body: String): String {
    if (body.isBlank()) return ""
    return try {
      val obj = JSONObject(body)
      when {
        obj.has("error") -> obj.optJSONObject("error")?.optString("message")?.trim().orEmpty()
          .ifBlank { obj.optString("message").trim() }
        obj.has("message") -> obj.optString("message").trim()
        else -> body.take(200).trim()
      }
    } catch (_: Throwable) {
      body.take(200).trim()
    }
  }

  private fun parseTextFromResponse(body: String): String {
    if (body.isBlank()) return ""
    return try {
      val obj = JSONObject(body)
      obj.optString("text", "").trim()
    } catch (_: Throwable) { "" }
  }

  private fun pcmToWav(pcm: ByteArray): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val dataSize = pcm.size
    val totalDataLen = dataSize + 36
    val out = ByteArrayOutputStream(44 + dataSize)
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
