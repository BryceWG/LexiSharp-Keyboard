package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Soniox 异步文件 ASR 引擎实现。
 * 录音 -> WAV -> 上传 /v1/files -> 创建转写 /v1/transcriptions -> 轮询完成 -> 拉取转写文本。
 */
class SonioxFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null,
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration), PcmBatchRecognizer {

    companion object {
        private const val TAG = "SonioxFileAsrEngine"
    }

    // Soniox：未明确限制，本地限制为 1 小时
    override val maxRecordDurationMillis: Int = 60 * 60 * 1000

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.sonioxApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_soniox_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        try {
            val wav = pcmToWav(pcm)
            val tmp = File.createTempFile("asr_soniox_", ".wav", context.cacheDir)
            FileOutputStream(tmp).use { it.write(wav) }

            val apiKey = prefs.sonioxApiKey
            val t0 = System.nanoTime()
            val fileId = uploadAudioFile(apiKey, tmp)
            val transcriptionId = createTranscription(apiKey, fileId)
            waitUntilCompleted(apiKey, transcriptionId)
            val text = getTranscriptionText(apiKey, transcriptionId)

            if (text.isNotBlank()) {
                val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                try {
                    onRequestDuration?.invoke(dt)
                } catch (_: Throwable) {}
                listener.onFinal(text)
            } else {
                listener.onError(context.getString(R.string.error_asr_empty_result))
            }
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""),
            )
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    private fun uploadAudioFile(apiKey: String, file: File): String {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/wav".toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url(Prefs.SONIOX_FILES_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()
        val resp = http.newCall(req).execute()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val detail = formatHttpDetail(r.message, extractErrorHint(body))
                throw RuntimeException(context.getString(R.string.error_request_failed_http, r.code, detail))
            }
            val id = try {
                JSONObject(body).optString("id").trim()
            } catch (_: Throwable) {
                ""
            }
            if (id.isBlank()) throw RuntimeException("uploadAudio: empty file id")
            return id
        }
    }

    private fun createTranscription(apiKey: String, fileId: String): String {
        val cfg = JSONObject().apply {
            put("file_id", fileId)
            put("model", "stt-async-v4")
            put("enable_language_identification", true)
            val langs = prefs.getSonioxLanguages()
            if (langs.isNotEmpty()) {
                val arr = org.json.JSONArray()
                langs.forEach { arr.put(it) }
                put("language_hints", arr)
                // 严格限制模式：仅允许识别指定语言，避免误转写为其他语言
                if (prefs.sonioxLanguageHintsStrict) {
                    put("language_hints_strict", true)
                }
            }
        }
        val req = Request.Builder()
            .url(Prefs.SONIOX_TRANSCRIPTIONS_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .post(cfg.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val resp = http.newCall(req).execute()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val detail = formatHttpDetail(r.message, extractErrorHint(body))
                throw RuntimeException(context.getString(R.string.error_request_failed_http, r.code, detail))
            }
            val id = try {
                JSONObject(body).optString("id").trim()
            } catch (_: Throwable) {
                ""
            }
            if (id.isBlank()) throw RuntimeException("createTranscription: empty id")
            return id
        }
    }

    private suspend fun waitUntilCompleted(apiKey: String, transcriptionId: String) {
        while (true) {
            val req = Request.Builder()
                .url(Prefs.SONIOX_TRANSCRIPTIONS_ENDPOINT + "/" + transcriptionId)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val resp = http.newCall(req).execute()
            resp.use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = formatHttpDetail(r.message, extractErrorHint(body))
                    throw RuntimeException(context.getString(R.string.error_request_failed_http, r.code, detail))
                }
                val status = try {
                    JSONObject(body).optString("status").lowercase()
                } catch (_: Throwable) {
                    ""
                }
                when (status) {
                    "completed" -> return
                    "error" -> {
                        val err = try {
                            JSONObject(body).optString("error_message")
                        } catch (_: Throwable) {
                            ""
                        }
                        throw RuntimeException("Soniox error: $err")
                    }
                }
            }
            delay(1000)
        }
    }

    private fun getTranscriptionText(apiKey: String, transcriptionId: String): String {
        val req = Request.Builder()
            .url(Prefs.SONIOX_TRANSCRIPTIONS_ENDPOINT + "/" + transcriptionId + "/transcript")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val resp = http.newCall(req).execute()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val detail = formatHttpDetail(r.message, extractErrorHint(body))
                throw RuntimeException(context.getString(R.string.error_request_failed_http, r.code, detail))
            }
            return parseTokensToText(body)
        }
    }

    /**
     * 从响应体中提取错误提示信息
     */
    private fun extractErrorHint(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            when {
                o.has("error_message") -> o.optString("error_message").trim()
                o.has("message") -> o.optString("message").trim()
                else -> body.take(200).trim()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse error hint", t)
            body.take(200).trim()
        }
    }

    /**
     * 从 Soniox 响应中解析转写文本
     */
    private fun parseTokensToText(body: String): String {
        return try {
            val o = JSONObject(body)
            val arr = o.optJSONArray("tokens") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val text = t.optString("text")
                if (text.isNotEmpty()) sb.append(text)
            }
            sb.toString().trim()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse tokens to text", t)
            ""
        }
    }
}
