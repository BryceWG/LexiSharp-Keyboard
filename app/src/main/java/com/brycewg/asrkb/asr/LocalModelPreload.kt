package com.brycewg.asrkb.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

// 本地模型就绪等待上限：用于避免“等待模型就绪”无限期阻塞后续兜底逻辑（如 Processing 超时）。
internal const val LOCAL_MODEL_READY_WAIT_MAX_MS = 60_000L

/**
 * 统一的本地 ASR 预加载入口：根据供应商调用对应实现。
 * - 目前支持 SenseVoice / FunASR Nano / TeleSpeech / Paraformer
 */
fun preloadLocalAsrIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false,
) {
    try {
        when (prefs.asrVendor) {
            AsrVendor.SenseVoice -> preloadSenseVoiceIfConfigured(
                context,
                prefs,
                onLoadStart,
                onLoadDone,
                suppressToastOnStart,
                forImmediateUse,
            )
            AsrVendor.FunAsrNano -> preloadFunAsrNanoIfConfigured(
                context,
                prefs,
                onLoadStart,
                onLoadDone,
                suppressToastOnStart,
                forImmediateUse,
            )
            AsrVendor.Telespeech -> preloadTelespeechIfConfigured(
                context,
                prefs,
                onLoadStart,
                onLoadDone,
                suppressToastOnStart,
                forImmediateUse,
            )
            AsrVendor.Paraformer -> preloadParaformerIfConfigured(
                context,
                prefs,
                onLoadStart,
                onLoadDone,
                suppressToastOnStart,
                forImmediateUse,
            )
            else -> { /* no-op for cloud vendors */ }
        }
    } catch (t: Throwable) {
        Log.e("LocalModelPreload", "preloadLocalAsrIfConfigured failed", t)
    }
}

/**
 * 统一检查本地 ASR 是否已准备（模型已加载或正在加载中）
 */
fun isLocalAsrPrepared(prefs: Prefs): Boolean {
    return try {
        when (prefs.asrVendor) {
            AsrVendor.SenseVoice -> isSenseVoicePrepared()
            AsrVendor.FunAsrNano -> isFunAsrNanoPrepared()
            AsrVendor.Telespeech -> isTelespeechPrepared()
            AsrVendor.Paraformer -> isParaformerPrepared()
            else -> false
        }
    } catch (t: Throwable) {
        Log.e("LocalModelPreload", "isLocalAsrPrepared failed", t)
        false
    }
}

/**
 * 判断当前 vendor 是否为“本地模型”。
 * 说明：这里的“本地模型”指需要在设备侧加载/准备模型（可能耗时较长）的 vendor。
 */
fun isLocalAsrVendor(vendor: AsrVendor): Boolean {
    return when (vendor) {
        AsrVendor.SenseVoice,
        AsrVendor.FunAsrNano,
        AsrVendor.Telespeech,
        AsrVendor.Paraformer,
        -> true
        else -> false
    }
}

/**
 * 本地 ASR 是否已就绪（模型已加载完成）。
 * 注意：与 [isLocalAsrPrepared] 不同，这里不把“正在加载中”视为就绪。
 */
fun isLocalAsrReady(prefs: Prefs): Boolean {
    return try {
        when (prefs.asrVendor) {
            AsrVendor.SenseVoice -> {
                val manager = SenseVoiceOnnxManager.getInstance()
                manager.isPrepared() && !manager.isPreparing()
            }
            AsrVendor.FunAsrNano -> {
                val manager = FunAsrNanoOnnxManager.getInstance()
                manager.isPrepared() && !manager.isPreparing()
            }
            AsrVendor.Telespeech -> {
                val manager = TelespeechOnnxManager.getInstance()
                manager.isPrepared() && !manager.isPreparing()
            }
            AsrVendor.Paraformer -> {
                val manager = ParaformerOnnxManager.getInstance()
                manager.isPrepared() && !manager.isPreparing()
            }
            else -> false
        }
    } catch (t: Throwable) {
        Log.e("LocalModelPreload", "isLocalAsrReady failed", t)
        false
    }
}

/**
 * 等待本地 ASR 进入“已就绪”状态。
 * - 非本地 vendor：直接返回 true
 * - 本地 vendor：仅在模型加载完成后返回 true
 *
 * @param maxWaitMs 0 表示不设上限（直到协程取消或模型就绪）
 * @return true 表示已就绪；false 表示超时（仅当 maxWaitMs > 0 时可能发生）
 */
suspend fun awaitLocalAsrReady(
    prefs: Prefs,
    pollIntervalMs: Long = 50L,
    maxWaitMs: Long = 0L,
): Boolean {
    val vendor = try {
        prefs.asrVendor
    } catch (t: Throwable) {
        Log.e("LocalModelPreload", "awaitLocalAsrReady: read vendor failed", t)
        return false
    }
    if (!isLocalAsrVendor(vendor)) return true
    if (isLocalAsrReady(prefs)) return true

    val interval = pollIntervalMs.coerceAtLeast(10L)
    val started = SystemClock.uptimeMillis()
    while (true) {
        currentCoroutineContext().ensureActive()
        if (isLocalAsrReady(prefs)) return true
        if (maxWaitMs > 0L) {
            val now = SystemClock.uptimeMillis()
            if ((now - started) >= maxWaitMs) return false
        }
        delay(interval)
    }
}
