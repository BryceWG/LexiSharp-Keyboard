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
 * 使用 ElevenLabs Speech-to-Text 多部分 API 的非流式 ASR 引擎
 * 文档: elevenlabs.md (POST /v1/speech-to-text, header: xi-api-key)
 */
class ElevenLabsFileAsrEngine(
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
        // 最大录音时长 5 分钟
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
        val tmp = File.createTempFile("asr_el_", ".wav", context.cacheDir)
        FileOutputStream(tmp).use { it.write(wav) }

        val apiKey = prefs.elevenApiKey
        if (apiKey.isBlank()) {
          listener.onError("未配置 ElevenLabs API Key")
          return@launch
        }

        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
          .addFormDataPart(
            "file",
            "audio.wav",
            tmp.asRequestBody("audio/wav".toMediaType())
          )

        val modelId = prefs.elevenModelId.trim()
        if (modelId.isEmpty()) {
          listener.onError("未配置 ElevenLabs Model ID（服务端要求必填）")
          return@launch
        }
        multipartBuilder.addFormDataPart("model_id", modelId)

        val request = Request.Builder()
          .url("https://api.elevenlabs.io/v1/speech-to-text")
          .addHeader("xi-api-key", apiKey)
          .post(multipartBuilder.build())
          .build()

        val t0 = System.nanoTime()
        val resp = http.newCall(request).execute()
        resp.use { r ->
          val bodyStr = r.body?.string().orEmpty()
          if (!r.isSuccessful) {
            val extra = extractErrorHint(bodyStr)
            val msg = r.message
            val reason = buildString {
              append("识别请求失败: HTTP ${r.code}")
              if (msg.isNotBlank()) append(": $msg")
              if (extra.isNotBlank()) append(" — $extra")
            }
            listener.onError(reason)
            return@use
          }
          val text = parseTextFromResponse(bodyStr)
          if (text.isNotBlank()) {
            val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
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

  private fun extractErrorHint(body: String): String {
    if (body.isBlank()) return ""
    return try {
      // Try object first
      val obj = JSONObject(body)
      when {
        obj.has("detail") -> obj.optString("detail").trim()
        obj.has("message") -> obj.optString("message").trim()
        obj.has("error") -> obj.optString("error").trim()
        else -> body.take(200).trim()
      }
    } catch (_: Throwable) {
      try {
        val arr = org.json.JSONArray(body)
        val msgs = mutableListOf<String>()
        for (i in 0 until minOf(arr.length(), 5)) {
          val e = arr.optJSONObject(i) ?: continue
          val loc = e.opt("loc")
          val msg = e.optString("msg").ifBlank { e.optString("message") }
          if (msg.isNotBlank()) {
            val locStr = when (loc) {
              is org.json.JSONArray -> (0 until loc.length()).joinToString(".") { loc.optString(it) }
              is String -> loc
              else -> ""
            }
            msgs.add(if (locStr.isNotBlank()) "$locStr: $msg" else msg)
          }
        }
        if (msgs.isNotEmpty()) msgs.joinToString("; ") else body.take(200).trim()
      } catch (_: Throwable) {
        body.take(200).trim()
      }
    }
  }

  private fun parseTextFromResponse(body: String): String {
    if (body.isBlank()) return ""
    return try {
      val obj = JSONObject(body)
      when {
        obj.has("text") -> obj.optString("text", "")
        obj.has("transcripts") -> {
          val arr = obj.optJSONArray("transcripts")
          if (arr != null && arr.length() > 0) {
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
              val item = arr.optJSONObject(i) ?: continue
              val t = item.optString("text").trim()
              if (t.isNotEmpty()) list.add(t)
            }
            list.joinToString("\n").trim()
          } else ""
        }
        else -> ""
      }
    } catch (_: Throwable) {
      ""
    }
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

