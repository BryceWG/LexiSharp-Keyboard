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
 * - 目前支持 SenseVoice / FunASR Nano / Qwen3-ASR / Parakeet / FireRedASR / X-ASR
 */
fun preloadLocalAsrIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false
) {
    try {
        AsrLocalVendorLifecycles.preload(
            prefs.asrVendor,
            AsrLocalVendorPreloadRequest.create(
                context = context,
                prefs = prefs,
                onLoadStart = onLoadStart,
                onLoadDone = onLoadDone,
                suppressToastOnStart = suppressToastOnStart,
                forImmediateUse = forImmediateUse
            )
        )
    } catch (t: Throwable) {
        Log.e("LocalModelPreload", "preloadLocalAsrIfConfigured failed", t)
    }
}

/**
 * 录音会话即将开始时的本地模型预热。
 *
 * 与“首次显示时预加载”不同，这里不依赖 UI 出现；只要本次会话会用本地模型，
 * 就尽早在录音阶段启动加载，让加载耗时尽量与用户说话时间重叠。
 */
fun preloadLocalAsrForImmediateUse(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null
) {
    val vendor = try {
        prefs.asrVendor
    } catch (t: Throwable) {
        Log.e("LocalModelPreload", "preloadLocalAsrForImmediateUse: read vendor failed", t)
        return
    }
    if (!isLocalAsrVendor(vendor)) return
    if (isLocalAsrPrepared(prefs)) return
    preloadLocalAsrIfConfigured(
        context = context,
        prefs = prefs,
        onLoadStart = onLoadStart,
        onLoadDone = onLoadDone,
        suppressToastOnStart = true,
        forImmediateUse = true
    )
}

/**
 * 统一检查本地 ASR 是否已准备（模型已加载或正在加载中）
 */
fun isLocalAsrPrepared(prefs: Prefs): Boolean = try {
    AsrLocalVendorLifecycles.isPrepared(prefs.asrVendor)
} catch (t: Throwable) {
    Log.e("LocalModelPreload", "isLocalAsrPrepared failed", t)
    false
}

/**
 * 判断当前 vendor 是否为“本地模型”。
 * 说明：这里的“本地模型”指需要在设备侧加载/准备模型（可能耗时较长）的 vendor。
 */
fun isLocalAsrVendor(vendor: AsrVendor): Boolean = AsrLocalVendorLifecycles.isLocalVendor(vendor)

/**
 * 本地 ASR 是否已就绪（模型已加载完成）。
 * 注意：与 [isLocalAsrPrepared] 不同，这里不把“正在加载中”视为就绪。
 */
fun isLocalAsrReady(prefs: Prefs): Boolean = try {
    AsrLocalVendorLifecycles.isReady(prefs.asrVendor)
} catch (t: Throwable) {
    Log.e("LocalModelPreload", "isLocalAsrReady failed", t)
    false
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
    maxWaitMs: Long = 0L
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
