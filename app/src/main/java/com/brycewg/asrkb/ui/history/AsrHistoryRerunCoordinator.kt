// Replays archived recognition audio and post-processing without creating usage events.
package com.brycewg.asrkb.ui.history

import android.content.Context
import android.os.SystemClock
import com.brycewg.asrkb.asr.AsrEngineConstructionSource
import com.brycewg.asrkb.asr.AsrEngineInvocationMode
import com.brycewg.asrkb.asr.AsrEngineModePreferences
import com.brycewg.asrkb.asr.AsrParallelEngineFactory
import com.brycewg.asrkb.asr.AsrPushPcmEngineFactory
import com.brycewg.asrkb.asr.BackupAwareAsrEngine
import com.brycewg.asrkb.asr.CancelableAsrEngine
import com.brycewg.asrkb.asr.ExternalPcmConsumer
import com.brycewg.asrkb.asr.StreamingAsrEngine
import com.brycewg.asrkb.store.AsrHistoryAudioStore
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.AsrFinalFilters
import com.brycewg.asrkb.util.TextSanitizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.isActive

internal class AsrHistoryRerunCoordinator(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val store = AsrHistoryStore(appContext)
    private val audioStore = AsrHistoryAudioStore(appContext)

    suspend fun reRecognize(record: AsrHistoryStore.AsrHistoryRecord): AsrHistoryStore.AsrHistoryRecord {
        val pcm = withContext(Dispatchers.IO) { audioStore.readAudio(record.id) }
            ?: error("audio_unavailable")
        val started = SystemClock.uptimeMillis()
        var requestMs = 0L
        val finalText = CompletableDeferred<String>()
        val listener = object : StreamingAsrEngine.Listener {
            override fun onFinal(text: String) { finalText.complete(text) }
            override fun onError(message: String) {
                finalText.completeExceptionally(IllegalStateException(message))
            }
        }
        val primary = prefs.asrVendor
        val batchPreferences = AsrEngineModePreferences()
        val engine = AsrParallelEngineFactory().createOrNull(
            context = appContext,
            scope = scope,
            prefs = prefs,
            listener = listener,
            primaryVendor = primary,
            backupVendor = prefs.backupAsrVendor,
            externalPcmInput = true,
            modePreferences = batchPreferences,
            onPrimaryRequestDuration = { requestMs = it }
        ) ?: AsrPushPcmEngineFactory().createOrNull(
            context = appContext,
            scope = scope,
            prefs = prefs,
            listener = listener,
            vendor = primary,
            invocationMode = AsrEngineInvocationMode.PushPcm,
            preferences = batchPreferences,
            source = AsrEngineConstructionSource.App,
            onRequestDuration = { requestMs = it }
        ) ?: error("engine_unavailable")

        try {
            engine.start()
            val consumer = engine as? ExternalPcmConsumer ?: error("engine_pcm_unsupported")
            pcm.asSequenceOfPcmChunks().forEach { chunk ->
                consumer.appendPcm(chunk, 16_000, 1)
            }
            engine.stop()
            val raw = withTimeout(120_000L) { finalText.await() }
            if (raw.isBlank()) error("empty_result")
            val processed = processNormal(raw)
            val actualVendor = when (engine) {
                is BackupAwareAsrEngine -> if (engine.wasLastResultFromBackup()) {
                    engine.backupVendor
                } else {
                    engine.primaryVendor
                }
                else -> primary
            }
            val updated = record.copy(
                rawText = raw,
                text = processed.text,
                vendorId = actualVendor.id,
                totalElapsedMs = (SystemClock.uptimeMillis() - started).coerceAtLeast(0L),
                procMs = requestMs,
                aiProcessed = processed.aiUsed,
                aiPostMs = processed.aiMs,
                aiPostStatus = processed.status,
                charCount = TextSanitizer.countEffectiveChars(processed.text)
            )
            return withContext(Dispatchers.IO) {
                store.updateById(record.id) { updated } ?: error("record_missing")
            }
        } finally {
            (engine as? CancelableAsrEngine)?.cancel() ?: runCatching { engine.stop() }
            if (!currentCoroutineContext().isActive) finalText.cancel()
        }
    }

    suspend fun reprocess(record: AsrHistoryStore.AsrHistoryRecord): AsrHistoryStore.AsrHistoryRecord {
        if (!prefs.hasLlmKeys()) error("llm_unavailable")
        val input = record.rawText?.takeIf { it.isNotBlank() } ?: record.text
        val result = AsrFinalFilters.applyWithAi(
            appContext,
            prefs,
            input,
            forceAi = true
        )
        if (!result.ok || result.text.isBlank()) error(result.errorMessage ?: "postprocess_failed")
        val updated = record.copy(
            text = result.text,
            aiProcessed = result.usedAi,
            aiPostMs = if (result.attempted) result.llmMs else 0L,
            aiPostStatus = when {
                result.attempted && result.usedAi -> AsrHistoryStore.AiPostStatus.SUCCESS
                result.attempted -> AsrHistoryStore.AiPostStatus.FAILED
                else -> AsrHistoryStore.AiPostStatus.NONE
            },
            charCount = TextSanitizer.countEffectiveChars(result.text)
        )
        return withContext(Dispatchers.IO) {
            store.updateById(record.id) { updated } ?: error("record_missing")
        }
    }

    private suspend fun processNormal(raw: String): ProcessedText {
        val result = AsrFinalFilters.applyWithAi(appContext, prefs, raw)
        val text = result.text.ifBlank { AsrFinalFilters.applySimple(appContext, prefs, raw) }
        val aiUsed = result.ok && result.usedAi
        return ProcessedText(
            text = text,
            aiUsed = aiUsed,
            aiMs = if (result.attempted) result.llmMs else 0L,
            status = when {
                result.attempted && aiUsed -> AsrHistoryStore.AiPostStatus.SUCCESS
                result.attempted -> AsrHistoryStore.AiPostStatus.FAILED
                else -> AsrHistoryStore.AiPostStatus.NONE
            }
        )
    }

    private data class ProcessedText(
        val text: String,
        val aiUsed: Boolean,
        val aiMs: Long,
        val status: AsrHistoryStore.AiPostStatus
    )

    private fun ByteArray.asSequenceOfPcmChunks(): Sequence<ByteArray> = sequence {
        val chunkBytes = 16_000 * 2 * 200 / 1_000
        var offset = 0
        while (offset < size) {
            val end = (offset + chunkBytes).coerceAtMost(size)
            yield(copyOfRange(offset, end))
            offset = end
        }
    }
}
