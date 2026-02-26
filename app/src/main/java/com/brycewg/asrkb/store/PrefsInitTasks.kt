package com.brycewg.asrkb.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Prefs 初始化阶段的“迁移/清理/调试监听”任务（从 [Prefs] 中拆出）。
 *
 * 说明：
 * - 当前仍保持与历史行为一致：构造 Prefs 即会触发这些任务。
 * - 后续若要消除 init 副作用，可将 [run] 改为由显式入口（如 App 启动/升级流程）调用。
 */
internal object PrefsInitTasks {
    private const val TAG = "Prefs"

    @Volatile private var toggleListenerRegistered: Boolean = false

    @Volatile private var fnLegacyCleanupStarted: Boolean = false

    private val globalToggleListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        try {
            if (!prefs.contains(key)) return@OnSharedPreferenceChangeListener
            val v = try {
                prefs.getBoolean(key, false)
            } catch (_: ClassCastException) {
                return@OnSharedPreferenceChangeListener
            }
            DebugLogManager.log("prefs", "toggle", mapOf("key" to key, "value" to v))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to log pref toggle", t)
        }
    }

    fun run(appContext: Context, sp: SharedPreferences) {
        registerGlobalToggleListenerIfNeeded(sp)
        migrateOnboardingGuideStateIfNeeded(sp)
        normalizeBackupAsrTimeoutSensitivityIfNeeded(sp)
        migrateFunAsrFromSenseVoiceIfNeeded(sp)
        normalizeFunAsrVariantIfNeeded(sp)
        cleanupLegacyFunAsrModelsIfNeeded(appContext, sp)
    }

    private fun normalizeBackupAsrTimeoutSensitivityIfNeeded(sp: SharedPreferences) {
        try {
            if (!sp.contains(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY)) return

            val currentInt = try {
                sp.getInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, 0)
            } catch (_: ClassCastException) {
                null
            }

            if (currentInt != null) {
                val clamped = currentInt.coerceIn(0, 2)
                if (clamped != currentInt) {
                    sp.edit { putInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, clamped) }
                }
                return
            }

            val currentLong = try {
                sp.getLong(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, 0L)
            } catch (_: ClassCastException) {
                null
            }
            val fromLong: Int? =
                currentLong?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

            val fromString = if (fromLong == null) {
                val s = try {
                    sp.getString(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, null)
                } catch (_: ClassCastException) {
                    null
                }
                s?.trim()?.toIntOrNull()
            } else {
                null
            }

            val normalized = (fromLong ?: fromString)?.coerceIn(0, 2)
            if (normalized == null) {
                sp.edit { remove(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY) }
                return
            }
            sp.edit { putInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, normalized) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to normalize backup ASR timeout sensitivity", t)
        }
    }

    private fun migrateOnboardingGuideStateIfNeeded(sp: SharedPreferences) {
        try {
            if (sp.getBoolean(KEY_SHOWN_ONBOARDING_GUIDE_V2_ONCE, false)) return

            val shownQuickGuide = sp.getBoolean(KEY_SHOWN_QUICK_GUIDE_ONCE, false)
            val shownModelGuide = sp.getBoolean(KEY_SHOWN_MODEL_GUIDE_ONCE, false)
            if (shownQuickGuide || shownModelGuide) {
                sp.edit { putBoolean(KEY_SHOWN_ONBOARDING_GUIDE_V2_ONCE, true) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate onboarding guide state", t)
        }
    }

    private fun registerGlobalToggleListenerIfNeeded(sp: SharedPreferences) {
        if (!toggleListenerRegistered) {
            try {
                sp.registerOnSharedPreferenceChangeListener(globalToggleListener)
                toggleListenerRegistered = true
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register global toggle listener", t)
            }
        }
    }

    fun migrateFunAsrFromSenseVoiceIfNeeded(sp: SharedPreferences) {
        try {
            if (sp.contains(KEY_FN_MODEL_VARIANT)) return
            val svVariant = sp.getString(KEY_SV_MODEL_VARIANT, "") ?: ""
            if (!svVariant.startsWith("nano-")) return

            sp.edit {
                putString(KEY_FN_MODEL_VARIANT, svVariant)
                putInt(KEY_FN_NUM_THREADS, sp.getInt(KEY_SV_NUM_THREADS, 4).coerceIn(1, 8))
                // FunASR Nano：ITN 由 LLM 输出承担即可，默认关闭；仅在用户曾显式开启 SenseVoice ITN 时继承为 true
                putBoolean(KEY_FN_USE_ITN, sp.getBoolean(KEY_SV_USE_ITN, false))
                putBoolean(KEY_FN_PRELOAD_ENABLED, sp.getBoolean(KEY_SV_PRELOAD_ENABLED, true))
                putInt(KEY_FN_KEEP_ALIVE_MINUTES, sp.getInt(KEY_SV_KEEP_ALIVE_MINUTES, -1))
                // 若当前选择为 SenseVoice 且使用 nano 变体，自动迁移到 FunASR Nano
                val currentVendor =
                    sp.getString(KEY_ASR_VENDOR, AsrVendor.SiliconFlow.id) ?: AsrVendor.SiliconFlow.id
                if (currentVendor == AsrVendor.SenseVoice.id) {
                    putString(KEY_ASR_VENDOR, AsrVendor.FunAsrNano.id)
                    putString(KEY_SV_MODEL_VARIANT, "small-int8")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to migrate FunASR Nano prefs from SenseVoice", t)
        }
    }

    private fun normalizeFunAsrVariantIfNeeded(sp: SharedPreferences) {
        try {
            if (!sp.contains(KEY_FN_MODEL_VARIANT)) return
            val variant = sp.getString(KEY_FN_MODEL_VARIANT, "nano-int8") ?: "nano-int8"
            if (variant == "nano-int8") return
            sp.edit { putString(KEY_FN_MODEL_VARIANT, "nano-int8") }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to normalize FunASR Nano variant", t)
        }
    }

    private fun cleanupLegacyFunAsrModelsIfNeeded(appContext: Context, sp: SharedPreferences) {
        try {
            if (sp.getBoolean(KEY_FN_LEGACY_MODEL_CLEANED, false)) return
            if (fnLegacyCleanupStarted) return
            fnLegacyCleanupStarted = true
            sp.edit { putBoolean(KEY_FN_LEGACY_MODEL_CLEANED, true) }
            val base = try {
                appContext.getExternalFilesDir(null)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to get external files dir for FunASR cleanup", t)
                null
            } ?: appContext.filesDir

            val legacyTargets = listOf(
                File(File(base, "sensevoice"), "nano-int8"),
                File(File(base, "sensevoice"), "nano-full"),
            )

            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                legacyTargets.forEach { dir ->
                    if (!dir.exists()) return@forEach
                    try {
                        if (!dir.deleteRecursively()) {
                            Log.w(TAG, "Failed to delete legacy FunASR dir: ${dir.path}")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error deleting legacy FunASR dir: ${dir.path}", t)
                    }
                }
                fnLegacyCleanupStarted = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to cleanup legacy FunASR models", t)
        }
    }
}
