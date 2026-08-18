/**
 * 豆包/火山引擎 ASR 模型目录与旧开关兼容。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.debug.DebugLogManager

internal enum class VolcAsrProtocolKind { Stream, StandardFile, TurboFile }

internal data class VolcLegacyFlags(
    val streamingEnabled: Boolean,
    val fileStandardEnabled: Boolean,
    val modelV2Enabled: Boolean
)

internal data class VolcAsrModel(
    val id: String,
    @param:StringRes val displayNameRes: Int,
    val streaming: Boolean,
    val modelV2: Boolean,
    val protocol: VolcAsrProtocolKind,
    val resourceId: String,
    val fileFallbackId: String?
)

internal object VolcAsrModelCatalog {
    const val DEFAULT_ID = "doubao_streaming_v2"

    private const val ID_STREAMING_V2 = "doubao_streaming_v2"
    private const val ID_STREAMING_V1 = "doubao_streaming_v1"
    private const val ID_FILE_V2 = "doubao_file_v2"
    private const val ID_FILE_V1 = "doubao_file_v1"
    private const val ID_FILE_TURBO_V1 = "doubao_file_turbo_v1"

    private val models: List<VolcAsrModel> = listOf(
        VolcAsrModel(
            id = ID_STREAMING_V2,
            displayNameRes = R.string.volc_asr_model_streaming_v2,
            streaming = true,
            modelV2 = true,
            protocol = VolcAsrProtocolKind.Stream,
            resourceId = "volc.seedasr.sauc.duration",
            fileFallbackId = ID_FILE_V2
        ),
        VolcAsrModel(
            id = ID_STREAMING_V1,
            displayNameRes = R.string.volc_asr_model_streaming_v1,
            streaming = true,
            modelV2 = false,
            protocol = VolcAsrProtocolKind.Stream,
            resourceId = "volc.bigasr.sauc.duration",
            fileFallbackId = ID_FILE_TURBO_V1
        ),
        VolcAsrModel(
            id = ID_FILE_V2,
            displayNameRes = R.string.volc_asr_model_file_v2,
            streaming = false,
            modelV2 = true,
            protocol = VolcAsrProtocolKind.StandardFile,
            resourceId = "volc.seedasr.auc",
            fileFallbackId = null
        ),
        VolcAsrModel(
            id = ID_FILE_V1,
            displayNameRes = R.string.volc_asr_model_file_v1,
            streaming = false,
            modelV2 = false,
            protocol = VolcAsrProtocolKind.StandardFile,
            resourceId = "volc.bigasr.auc",
            fileFallbackId = null
        ),
        VolcAsrModel(
            id = ID_FILE_TURBO_V1,
            displayNameRes = R.string.volc_asr_model_file_turbo_v1,
            streaming = false,
            modelV2 = false,
            protocol = VolcAsrProtocolKind.TurboFile,
            resourceId = "volc.bigasr.auc_turbo",
            fileFallbackId = null
        )
    )

    private val modelsById: Map<String, VolcAsrModel> = models.associateBy { it.id }

    fun all(): List<VolcAsrModel> = models

    fun fromId(id: String): VolcAsrModel? = modelsById[id]

    fun fromIdOrDefault(id: String): VolcAsrModel {
        fromId(id)?.let { return it }
        DebugLogManager.logWarning(
            category = "asr",
            event = "volc_model_unknown",
            data = mapOf("raw" to id, "fallback" to DEFAULT_ID)
        )
        return modelsById.getValue(DEFAULT_ID)
    }

    fun fromLegacyFlags(streaming: Boolean, fileStandard: Boolean, modelV2: Boolean): VolcAsrModel {
        val id = when {
            streaming && modelV2 -> ID_STREAMING_V2
            streaming && !modelV2 -> ID_STREAMING_V1
            !streaming && fileStandard && modelV2 -> ID_FILE_V2
            !streaming && fileStandard && !modelV2 -> ID_FILE_V1
            else -> ID_FILE_TURBO_V1
        }
        return modelsById.getValue(id)
    }

    fun isStreaming(id: String): Boolean = fromIdOrDefault(id).streaming

    fun fileFallback(id: String): VolcAsrModel? {
        val fallbackId = fromId(id)?.fileFallbackId ?: return null
        return fromId(fallbackId)
    }

    fun legacyFlags(id: String): VolcLegacyFlags {
        val model = fromIdOrDefault(id)
        return when (model.protocol) {
            VolcAsrProtocolKind.Stream -> VolcLegacyFlags(
                streamingEnabled = true,
                fileStandardEnabled = true,
                modelV2Enabled = model.modelV2
            )
            VolcAsrProtocolKind.StandardFile -> VolcLegacyFlags(
                streamingEnabled = false,
                fileStandardEnabled = true,
                modelV2Enabled = model.modelV2
            )
            VolcAsrProtocolKind.TurboFile -> VolcLegacyFlags(
                streamingEnabled = false,
                fileStandardEnabled = false,
                modelV2Enabled = false
            )
        }
    }
}
