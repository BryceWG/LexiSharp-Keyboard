package com.brycewg.asrkb.ui.settings.other

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.SpeechPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for OtherSettingsActivity that manages speech presets and sync clipboard settings.
 * Uses StateFlow to drive reactive UI updates and eliminates manual UI refresh complexity.
 */
class OtherSettingsViewModel(private val prefs: Prefs) : ViewModel() {

    companion object {
        private const val TAG = "OtherSettingsViewModel"
    }

    // Speech presets state
    private val _speechPresetsState = MutableStateFlow(SpeechPresetsState())
    val speechPresetsState: StateFlow<SpeechPresetsState> = _speechPresetsState.asStateFlow()

    // Sync clipboard state
    private val _syncClipboardState = MutableStateFlow(SyncClipboardState())
    val syncClipboardState: StateFlow<SyncClipboardState> = _syncClipboardState.asStateFlow()

    data class SpeechPresetsState(
        val presets: List<SpeechPreset> = emptyList(),
        val activePresetId: String = "",
        val currentPreset: SpeechPreset? = null,
        val isEnabled: Boolean = false,
    )

    data class SyncClipboardState(
        val enabled: Boolean = false,
        val serverBase: String = "",
        val username: String = "",
        val password: String = "",
        val autoPullEnabled: Boolean = false,
        val pullIntervalSec: Int = 15,
    )

    init {
        loadSpeechPresets()
        loadSyncClipboardSettings()
    }

    // Speech Presets Management

    private fun loadSpeechPresets() {
        viewModelScope.launch {
            try {
                val presets = prefs.getSpeechPresets()
                val activeId = prefs.activeSpeechPresetId
                val current = if (presets.isNotEmpty()) {
                    presets.firstOrNull { it.id == activeId } ?: presets.firstOrNull()
                } else {
                    null
                }

                // Update active ID if it changed (when first preset is auto-selected)
                if (current != null && prefs.activeSpeechPresetId != current.id) {
                    prefs.activeSpeechPresetId = current.id
                }

                _speechPresetsState.value = SpeechPresetsState(
                    presets = presets,
                    activePresetId = current?.id ?: "",
                    currentPreset = current,
                    isEnabled = presets.isNotEmpty(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load speech presets", e)
            }
        }
    }

    fun addSpeechPreset(defaultName: String) {
        viewModelScope.launch {
            try {
                val list = prefs.getSpeechPresets().toMutableList()
                val newId = java.util.UUID.randomUUID().toString()
                list.add(SpeechPreset(newId, defaultName, ""))
                prefs.setSpeechPresets(list)
                prefs.activeSpeechPresetId = newId
                loadSpeechPresets()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add speech preset", e)
            }
        }
    }

    fun deleteSpeechPreset(presetId: String) {
        viewModelScope.launch {
            try {
                val list = prefs.getSpeechPresets().toMutableList()
                val idx = list.indexOfFirst { it.id == presetId }
                if (idx >= 0) {
                    list.removeAt(idx)
                    prefs.setSpeechPresets(list)
                    if (list.isNotEmpty()) {
                        val nextIdx = idx.coerceAtMost(list.lastIndex)
                        prefs.activeSpeechPresetId = list[nextIdx].id
                    } else {
                        prefs.activeSpeechPresetId = ""
                    }
                    loadSpeechPresets()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete speech preset", e)
            }
        }
    }

    fun updateActivePresetName(name: String) {
        viewModelScope.launch {
            try {
                val list = prefs.getSpeechPresets().toMutableList()
                val idx = list.indexOfFirst { it.id == prefs.activeSpeechPresetId }
                if (idx < 0) return@launch

                val current = list[idx]
                // 允许名称为空：为空时只更新 UI 状态，不立刻持久化，避免被清除
                if (name.isEmpty()) {
                    val mutated = current.copy(name = "")
                    val uiList = list.toMutableList()
                    uiList[idx] = mutated
                    _speechPresetsState.value = _speechPresetsState.value.copy(
                        presets = uiList,
                        activePresetId = prefs.activeSpeechPresetId,
                        currentPreset = mutated,
                        isEnabled = uiList.isNotEmpty(),
                    )
                    return@launch
                }

                val newName = name
                if (current.name == newName) {
                    return@launch
                }

                val mutated = current.copy(name = newName)
                list[idx] = mutated
                prefs.setSpeechPresets(list)

                // 刷新状态
                loadSpeechPresets()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update preset name", e)
            }
        }
    }

    fun updateActivePresetContent(content: String) {
        viewModelScope.launch {
            try {
                val list = prefs.getSpeechPresets().toMutableList()
                val idx = list.indexOfFirst { it.id == prefs.activeSpeechPresetId }
                if (idx < 0) return@launch

                val current = list[idx]
                if (current.content == content) return@launch

                val mutated = current.copy(content = content)
                list[idx] = mutated
                prefs.setSpeechPresets(list)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update preset content", e)
            }
        }
    }

    fun setActivePreset(presetId: String) {
        viewModelScope.launch {
            try {
                prefs.activeSpeechPresetId = presetId
                loadSpeechPresets()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set active preset", e)
            }
        }
    }

    // Sync Clipboard Management

    private fun loadSyncClipboardSettings() {
        viewModelScope.launch {
            try {
                _syncClipboardState.value = SyncClipboardState(
                    enabled = prefs.syncClipboardEnabled,
                    serverBase = prefs.syncClipboardServerBase,
                    username = prefs.syncClipboardUsername,
                    password = prefs.syncClipboardPassword,
                    autoPullEnabled = prefs.syncClipboardAutoPullEnabled,
                    pullIntervalSec = prefs.syncClipboardPullIntervalSec,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sync clipboard settings", e)
            }
        }
    }

    fun updateSyncClipboardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                prefs.syncClipboardEnabled = enabled
                _syncClipboardState.value = _syncClipboardState.value.copy(enabled = enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update sync clipboard enabled", e)
            }
        }
    }

    fun updateSyncClipboardServerBase(serverBase: String) {
        viewModelScope.launch {
            try {
                prefs.syncClipboardServerBase = serverBase
                _syncClipboardState.value = _syncClipboardState.value.copy(serverBase = serverBase)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update sync clipboard server base", e)
            }
        }
    }

    fun updateSyncClipboardUsername(username: String) {
        viewModelScope.launch {
            try {
                prefs.syncClipboardUsername = username
                _syncClipboardState.value = _syncClipboardState.value.copy(username = username)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update sync clipboard username", e)
            }
        }
    }

    fun updateSyncClipboardPassword(password: String) {
        viewModelScope.launch {
            try {
                prefs.syncClipboardPassword = password
                _syncClipboardState.value = _syncClipboardState.value.copy(password = password)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update sync clipboard password", e)
            }
        }
    }

    fun updateSyncClipboardAutoPullEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                prefs.syncClipboardAutoPullEnabled = enabled
                _syncClipboardState.value = _syncClipboardState.value.copy(autoPullEnabled = enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update sync clipboard auto pull enabled", e)
            }
        }
    }

    fun updateSyncClipboardPullIntervalSec(intervalSec: Int) {
        viewModelScope.launch {
            try {
                val coerced = intervalSec.coerceIn(1, 600)
                prefs.syncClipboardPullIntervalSec = coerced
                _syncClipboardState.value = _syncClipboardState.value.copy(pullIntervalSec = coerced)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update sync clipboard pull interval", e)
            }
        }
    }
}
