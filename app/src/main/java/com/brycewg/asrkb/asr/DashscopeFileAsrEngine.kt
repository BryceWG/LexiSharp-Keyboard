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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 使用阿里云百炼（DashScope）临时上传 + 生成接口的非流式 ASR 引擎。
 * 流程：
 * 1) 录音 -> PCM -> WAV
 * 2) GET /api/v1/uploads?action=getPolicy&model=xxx 获取临时上传策略
 * 3) 按策略表单上传到 OSS（policy.upload_host）
 * 4) POST /api/v1/services/aigc/multimodal-generation/generation，携带 oss:// 路径
 */
class DashscopeFileAsrEngine(
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
        val tmp = File.createTempFile("asr_dash_", ".wav", context.cacheDir)
        FileOutputStream(tmp).use { it.write(wav) }

        val apiKey = prefs.dashApiKey
        if (apiKey.isBlank()) {
          listener.onError(context.getString(R.string.error_missing_dashscope_key))
          return@launch
        }
        val model = prefs.dashModel.ifBlank { Prefs.DEFAULT_DASH_MODEL }

        // 1) 获取临时上传策略
        val policyUrl = "https://dashscope.aliyuncs.com/api/v1/uploads?action=getPolicy&model=" +
          java.net.URLEncoder.encode(model, "UTF-8")
        val policyReq = Request.Builder()
          .url(policyUrl)
          .get()
          .addHeader("Authorization", "Bearer $apiKey")
          .addHeader("Content-Type", "application/json")
          .build()
        val policyResp = http.newCall(policyReq).execute()
        val policyBody = policyResp.body?.string().orEmpty()
        if (!policyResp.isSuccessful) {
          val msg = policyResp.message
          val suffix = if (msg.isBlank()) "" else ": $msg"
          listener.onError(context.getString(R.string.error_dash_getpolicy_failed_http, policyResp.code, suffix))
          return@launch
        }
        val policyJson = try { JSONObject(policyBody) } catch (_: Throwable) { JSONObject() }
        val policy = policyJson.optJSONObject("data")
        if (policy == null) {
          listener.onError(context.getString(R.string.error_dash_no_data))
          return@launch
        }

        // 2) 表单上传到 OSS
        val uploadDir = policy.optString("upload_dir").trim('/',' ')
        val key = if (uploadDir.isNotEmpty())
          "$uploadDir/" + (tmp.nameIfExists())
        else tmp.nameIfExists()
        val ossHost = policy.optString("upload_host")
        if (ossHost.isBlank()) {
          listener.onError(context.getString(R.string.error_dash_missing_upload_host))
          return@launch
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
          .addFormDataPart("OSSAccessKeyId", policy.optString("oss_access_key_id"))
          .addFormDataPart("Signature", policy.optString("signature"))
          .addFormDataPart("policy", policy.optString("policy"))
          .apply {
            if (policy.has("x_oss_object_acl"))
              addFormDataPart("x-oss-object-acl", policy.optString("x_oss_object_acl"))
            if (policy.has("x_oss_forbid_overwrite"))
              addFormDataPart("x-oss-forbid-overwrite", policy.optString("x_oss_forbid_overwrite"))
            if (policy.has("x_oss_security_token"))
              addFormDataPart("x-oss-security-token", policy.optString("x_oss_security_token"))
          }
          .addFormDataPart("key", key)
          .addFormDataPart("success_action_status", "200")
          .addFormDataPart(
            "file",
            "audio.wav",
            tmp.asRequestBody("audio/wav".toMediaType())
          )
          .build()
        val ossReq = Request.Builder()
          .url(ossHost)
          .post(multipart)
          .build()
        val ossResp = http.newCall(ossReq).execute()
        if (!ossResp.isSuccessful) {
          val err = ossResp.body?.string().orEmpty().ifBlank { ossResp.message }
          val suffix = if (err.isBlank()) "" else ": $err"
          listener.onError(context.getString(R.string.error_oss_upload_failed_http, ossResp.code, suffix))
          return@launch
        }
        val ossUrl = "oss://$key"

        // 3) 调用生成接口进行识别
        val asrOptions = JSONObject().apply {
          put("enable_lid", true)
          // ITN 默认关闭；如需启用可在后续扩展设置
          put("enable_itn", false)
        }
        val systemMsg = JSONObject().apply {
          put("text", "")
        }
        val userMsg = JSONObject().apply {
          put("audio", ossUrl)
        }
        val bodyObj = JSONObject().apply {
          put("model", model)
          put("input", JSONObject().apply {
            put("messages", org.json.JSONArray().apply {
              put(JSONObject().apply {
                put("role", "system")
                put("content", org.json.JSONArray().apply { put(systemMsg) })
              })
              put(JSONObject().apply {
                put("role", "user")
                put("content", org.json.JSONArray().apply { put(userMsg) })
              })
            })
          })
          put("parameters", JSONObject().apply {
            put("asr_options", asrOptions)
          })
        }
        val genReq = Request.Builder()
          .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
          .addHeader("Authorization", "Bearer $apiKey")
          .addHeader("Content-Type", "application/json")
          .addHeader("X-DashScope-OssResourceResolve", "enable")
          .post(bodyObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
          .build()
        val t0 = System.nanoTime()
        val genResp = http.newCall(genReq).execute()
        val genStr = genResp.body?.string().orEmpty()
        if (!genResp.isSuccessful) {
          val msg = genResp.message
          val suffix = if (msg.isBlank()) "" else ": $msg"
          listener.onError(context.getString(R.string.error_asr_request_failed_http, genResp.code, suffix))
          return@launch
        }
        val text = parseDashscopeText(genStr)
        if (text.isNotBlank()) {
          val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
          try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
          listener.onFinal(text)
        } else {
          listener.onError(context.getString(R.string.error_asr_empty_result))
        }
      } catch (t: Throwable) {
        listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
      }
    }
  }

  private fun parseDashscopeText(body: String): String {
    if (body.isBlank()) return ""
    return try {
      val obj = JSONObject(body)
      val output = obj.optJSONObject("output") ?: return ""
      val choices = output.optJSONArray("choices") ?: return ""
      if (choices.length() == 0) return ""
      val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
      val content = msg.optJSONArray("content") ?: return ""
      var txt = ""
      for (i in 0 until content.length()) {
        val it = content.optJSONObject(i) ?: continue
        if (it.has("text")) {
          txt = it.optString("text").trim()
          if (txt.isNotEmpty()) break
        }
      }
      txt
    } catch (_: Throwable) { "" }
  }

  private fun File.nameIfExists(): String {
    return try { name } catch (_: Throwable) { "upload.wav" }
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
