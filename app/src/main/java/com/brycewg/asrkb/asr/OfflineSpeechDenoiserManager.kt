package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OfflineSpeechDenoiserManager {
    private const val TAG = "OfflineDenoiser"
    private const val MODEL_ASSET_PATH = "denoiser/gtcrn_simple.onnx"

    @Volatile private var denoiser: Any? = null

    @Volatile private var denoiserClass: Class<*>? = null

    @Volatile private var loadFailed: Boolean = false
    private val runLock = Any()

    fun denoiseIfEnabled(
        context: Context,
        prefs: Prefs,
        pcm: ByteArray,
        sampleRate: Int,
    ): ByteArray {
        if (!prefs.offlineDenoiseEnabled) return pcm
        if (pcm.isEmpty() || sampleRate <= 0) return pcm

        val denoiser = getOrCreate(context) ?: return pcm
        val samples = pcm16leToFloatArray(pcm)
        if (samples.isEmpty()) return pcm

        return try {
            synchronized(runLock) {
                val cls = denoiserClass ?: return pcm
                val runMethod = cls.getMethod(
                    "run",
                    FloatArray::class.java,
                    Int::class.javaPrimitiveType,
                )
                val out = runMethod.invoke(denoiser, samples, sampleRate) ?: return pcm
                val outSamples = out.javaClass.getMethod("getSamples").invoke(out) as? FloatArray ?: return pcm
                val outRate = out.javaClass.getMethod("getSampleRate").invoke(out) as? Int ?: sampleRate
                if (outRate != sampleRate) {
                    Log.w(TAG, "Denoiser output sampleRate=$outRate mismatch input=$sampleRate, skip")
                    return pcm
                }
                floatArrayToPcm16le(outSamples)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Offline denoise failed", t)
            pcm
        }
    }

    private fun getOrCreate(context: Context): Any? {
        if (denoiser != null || loadFailed) return denoiser
        synchronized(this) {
            if (denoiser != null || loadFailed) return denoiser
            return try {
                try {
                    System.loadLibrary("sherpa-onnx-jni")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load sherpa-onnx-jni", t)
                }

                val gtcrnClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig")
                val modelClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig")
                val configClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig")
                val denoiserCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser")

                val gtcrn = gtcrnClass.getDeclaredConstructor().newInstance()
                setField(gtcrn, "model", MODEL_ASSET_PATH)

                val modelConfig = modelClass.getDeclaredConstructor().newInstance()
                setField(modelConfig, "gtcrn", gtcrn)
                setField(modelConfig, "numThreads", 1)
                setField(modelConfig, "debug", false)
                setField(modelConfig, "provider", "cpu")

                val config = configClass.getDeclaredConstructor().newInstance()
                setField(config, "model", modelConfig)

                val ctor = denoiserCls.getDeclaredConstructor(
                    android.content.res.AssetManager::class.java,
                    configClass,
                )
                val instance = ctor.newInstance(context.assets, config)
                denoiser = instance
                denoiserClass = denoiserCls
                Log.i(TAG, "Offline denoiser loaded: $MODEL_ASSET_PATH")
                instance
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize offline denoiser", t)
                loadFailed = true
                denoiser = null
                null
            }
        }
    }

    private fun setField(target: Any, name: String, value: Any?): Boolean {
        return try {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(target, value)
            true
        } catch (t: Throwable) {
            try {
                val methodName = "set" + name.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
                val paramType = when (value) {
                    is Int -> Int::class.javaPrimitiveType
                    is Boolean -> Boolean::class.javaPrimitiveType
                    is Float -> Float::class.javaPrimitiveType
                    is Double -> Double::class.javaPrimitiveType
                    is Long -> Long::class.javaPrimitiveType
                    is String -> String::class.java
                    else -> value?.javaClass ?: Any::class.java
                }
                val method = target.javaClass.getMethod(methodName, paramType)
                method.invoke(target, value)
                true
            } catch (t2: Throwable) {
                Log.w(TAG, "Failed to set field $name", t2)
                false
            }
        }
    }

    private fun pcm16leToFloatArray(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val n = pcm.size / 2
        val out = FloatArray(n)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < n) {
            val s = bb.short.toInt()
            var f = s / 32768.0f
            if (f > 1f) f = 1f else if (f < -1f) f = -1f
            out[i] = f
            i++
        }
        return out
    }

    private fun floatArrayToPcm16le(samples: FloatArray): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        val out = ByteArray(samples.size * 2)
        var i = 0
        var j = 0
        while (i < samples.size) {
            val f = samples[i].coerceIn(-1f, 1f)
            val v = (f * 32767f).toInt()
            out[j] = (v and 0xFF).toByte()
            out[j + 1] = ((v shr 8) and 0xFF).toByte()
            i++
            j += 2
        }
        return out
    }
}
