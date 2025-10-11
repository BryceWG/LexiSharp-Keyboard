package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 使用 Google Gemini generateContent 的非流式 ASR 引擎（通过提示词进行转录）。
 * 流程：start() 开始录音，stop() 结束后将整段音频（WAV, inline base64）发送给 Gemini，请求转写文本。
 */
class GeminiFileAsrEngine(
  private val context: Context,
  private val scope: CoroutineScope,
  private val prefs: Prefs,
  private val listener: StreamingAsrEngine.Listener,
  private val onRequestDuration: ((Long) -> Unit)? = null
) : StreamingAsrEngine {

  private val http: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(90, TimeUnit.SECONDS)
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
        // 软限制最大约 5 分钟 (~9.6MB WAV，低于 20MB inline 限制)
        val maxBytes = 5 * 60 * sampleRate * 2
      while (true) {
        if (!running.get()) break
        val read = recorder.read(buf, 0, buf.size)
        if (read > 0) {
          pcmBuffer.write(buf, 0, read)
          try { listener.onAudioLevel(rmsOfPcm16(buf, read)) } catch (_: Throwable) { }
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
        val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        val apiKey = prefs.gemApiKey
        if (apiKey.isBlank()) {
          listener.onError(context.getString(R.string.error_missing_gemini_key))
          return@launch
        }
        val model = prefs.gemModel.ifBlank { Prefs.DEFAULT_GEM_MODEL }
        val prompt = prefs.gemPrompt.ifBlank { DEFAULT_GEM_PROMPT }

        val body = buildGeminiRequestBody(b64, prompt)
        val req = Request.Builder()
          .url("${GEM_BASE}/models/${model}:generateContent?key=${apiKey}")
          .addHeader("Content-Type", "application/json; charset=utf-8")
          .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
          .build()
        val t0 = System.nanoTime()
        val resp = http.newCall(req).execute()
        resp.use { r ->
          val str = r.body?.string().orEmpty()
          if (!r.isSuccessful) {
            val hint = extractGeminiError(str)
            val msg = r.message
            val suffix = buildString {
              if (msg.isNotBlank()) append(": $msg")
              if (hint.isNotBlank()) append(" — $hint")
            }
            listener.onError(context.getString(R.string.error_request_failed_http, r.code, suffix))
            return@use
          }
          val text = parseGeminiText(str)
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

  private fun buildGeminiRequestBody(base64Wav: String, prompt: String): String {
    val inlineAudio = JSONObject().apply {
      put("inline_data", JSONObject().apply {
        put("mime_type", "audio/wav")
        put("data", base64Wav)
      })
    }
    val promptPart = JSONObject().apply { put("text", prompt) }
    val user = JSONObject().apply {
      put("role", "user")
      put("parts", org.json.JSONArray().apply {
        // 提示可放前后均可；保持先文本后音频
        put(promptPart)
        put(inlineAudio)
      })
    }
    val root = JSONObject().apply {
      put("contents", org.json.JSONArray().apply { put(user) })
      // 为了稳定性，温度设低
      put("generation_config", JSONObject().apply { put("temperature", 0) })
    }
    return root.toString()
  }

  private fun extractGeminiError(body: String): String {
    if (body.isBlank()) return ""
    return try {
      val o = JSONObject(body)
      if (o.has("error")) {
        val e = o.optJSONObject("error")
        val msg = e?.optString("message").orEmpty()
        val status = e?.optString("status").orEmpty()
        listOf(status, msg).filter { it.isNotBlank() }.joinToString(": ")
      } else body.take(200).trim()
    } catch (_: Throwable) {
      body.take(200).trim()
    }
  }

  private fun parseGeminiText(body: String): String {
    if (body.isBlank()) return ""
    return try {
      val o = JSONObject(body)
      val cands = o.optJSONArray("candidates") ?: return ""
      if (cands.length() == 0) return ""
      val cand0 = cands.optJSONObject(0) ?: return ""
      val content = cand0.optJSONObject("content") ?: return ""
      val parts = content.optJSONArray("parts") ?: return ""
      var txt = ""
      for (i in 0 until parts.length()) {
        val p = parts.optJSONObject(i) ?: continue
        val t = p.optString("text").trim()
        if (t.isNotEmpty()) { txt = t; break }
      }
      txt
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

  companion object {
    private const val GEM_BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val DEFAULT_GEM_PROMPT = "请将以下音频逐字转写为文本，不要输出解释或前后缀。输入语言可能是中文、英文或其他语言"
  }

  private fun rmsOfPcm16(buf: ByteArray, count: Int): Float {
    var sum = 0.0
    var n = 0
    var i = 0
    val limit = count - (count % 2)
    while (i + 1 < limit) {
      val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
      sum += (s * s).toDouble()
      n++
      i += 2
    }
    if (n == 0) return 0f
    val mean = sum / n
    val rms = kotlin.math.sqrt(mean).toFloat()
    return (rms / 32768f).coerceIn(0f, 1f)
  }
}
