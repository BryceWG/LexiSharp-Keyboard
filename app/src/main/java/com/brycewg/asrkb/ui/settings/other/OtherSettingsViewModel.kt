package com.brycewg.asrkb.ui.settings.other

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brycewg.asrkb.clipboard.ClipboardSyncReceiveMode
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.SpeechPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Compose other settings screen that manages speech presets and sync clipboard settings.
 * Uses StateFlow to drive reactive UI updates and eliminates manual UI refresh complexity.
 */
class OtherSettingsViewModel(
    private val prefs: Prefs,
    private val onSyncClipboardChanged: () -> Unit = {}
) : ViewModel() {

    companion object {
        private const val TAG = "OtherSettingsViewModel"
    }

    // Speech presets state
    private val _speechPresetsState = MutableStateFlow(buildSpeechPresetsStateSafely())
    val speechPresetsState: StateFlow<SpeechPresetsState> = _speechPresetsState.asStateFlow()

    // Sync clipboard state
    private val _syncClipboardState = MutableStateFlow(buildSyncClipboardStateSafely())
    val syncClipboardState: StateFlow<SyncClipboardState> = _syncClipboardState.asStateFlow()
    private var speechPresetPersistJob: Job? = null
    private var syncClipboardCapabilityObserverJob: Job? = null

    init {
        if (_syncClipboardState.value.enabled &&
            _syncClipboardState.value.receiveMode != ClipboardSyncReceiveMode.OFF &&
            prefs.syncClipboardRealtimeSupported == null
        ) {
            requestSyncClipboardCapabilityProbe()
        }
    }

    data class SpeechPresetsState(
        val presets: List<SpeechPreset> = emptyList(),
        val activePresetId: String = "",
        val currentPreset: SpeechPreset? = null,
        val isEnabled: Boolean = false
    )

    data class SyncClipboardState(
        val enabled: Boolean = false,
        val serverBase: String = "",
        val username: String = "",
        val password: String = "",
        val receiveMode: ClipboardSyncReceiveMode = ClipboardSyncReceiveMode.OFF,
        val detectingReceiveMode: Boolean = false,
        val keepBackgroundRealtimeEnabled: Boolean = false,
        val pullIntervalSec: Int = 15,
        val syncImagesEnabled: Boolean = false,
        val syncFilesEnabled: Boolean = false,
        val attachmentMaxSizeMb: Int = 50,
        val watchTreeUri: String = ""
    )

    // Speech Presets Management

    private fun loadSpeechPresets() {
        viewModelScope.launch {
            try {
                _speechPresetsState.value = buildSpeechPresetsState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load speech presets", e)
            }
        }
    }

    private fun buildSpeechPresetsState(): SpeechPresetsState {
        val presets = prefs.getSpeechPresets()
        val activeId = prefs.activeSpeechPresetId
        val current = if (presets.isNotEmpty()) {
            presets.firstOrNull { it.id == activeId } ?: presets.firstOrNull()
        } else {
            null
        }

        if (current != null && prefs.activeSpeechPresetId != current.id) {
            prefs.activeSpeechPresetId = current.id
        }

        return SpeechPresetsState(
            presets = presets,
            activePresetId = current?.id ?: "",
            currentPreset = current,
            isEnabled = presets.isNotEmpty()
        )
    }

    private fun buildSpeechPresetsStateSafely(): SpeechPresetsState = try {
        buildSpeechPresetsState()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to build speech presets state", e)
        SpeechPresetsState()
    }

    fun addSpeechPreset(defaultName: String) {
        viewModelScope.launch {
            try {
                flushPendingSpeechPreset()
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
                flushPendingSpeechPreset()
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
        try {
            updateActiveSpeechPresetState { it.copy(name = name) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preset name", e)
        }
    }

    fun updateActivePresetContent(content: String) {
        try {
            updateActiveSpeechPresetState { it.copy(content = content) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preset content", e)
        }
    }

    fun setActivePreset(presetId: String) {
        viewModelScope.launch {
            try {
                flushPendingSpeechPreset()
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
                _syncClipboardState.value = buildSyncClipboardState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sync clipboard settings", e)
            }
        }
    }

    private fun updateActiveSpeechPresetState(mutator: (SpeechPreset) -> SpeechPreset) {
        val state = _speechPresetsState.value
        val activeId = state.activePresetId.ifBlank { prefs.activeSpeechPresetId }
        val list = state.presets.toMutableList()
        val idx = list.indexOfFirst { it.id == activeId }
        if (idx < 0) return

        val mutated = mutator(list[idx])
        if (mutated == list[idx]) return

        list[idx] = mutated
        _speechPresetsState.value = state.copy(
            presets = list,
            activePresetId = activeId,
            currentPreset = mutated,
            isEnabled = list.isNotEmpty()
        )

        if (mutated.name.isNotBlank()) {
            scheduleSpeechPresetPersist(list)
        } else {
            speechPresetPersistJob?.cancel()
        }
    }

    private fun scheduleSpeechPresetPersist(list: List<SpeechPreset>) {
        speechPresetPersistJob?.cancel()
        speechPresetPersistJob = viewModelScope.launch {
            delay(350L)
            prefs.setSpeechPresets(list)
            speechPresetPersistJob = null
        }
    }

    private fun flushPendingSpeechPreset() {
        speechPresetPersistJob?.cancel()
        speechPresetPersistJob = null
        val state = _speechPresetsState.value
        if (state.currentPreset?.name?.isNotBlank() == true) {
            prefs.setSpeechPresets(state.presets)
        }
    }

    private fun buildSyncClipboardState(): SyncClipboardState = SyncClipboardState(
        enabled = prefs.syncClipboardEnabled,
        serverBase = prefs.syncClipboardServerBase,
        username = prefs.syncClipboardUsername,
        password = prefs.syncClipboardPassword,
        receiveMode = prefs.syncClipboardReceiveMode,
        keepBackgroundRealtimeEnabled = prefs.syncClipboardKeepBackgroundRealtimeEnabled,
        pullIntervalSec = prefs.syncClipboardPullIntervalSec,
        syncImagesEnabled = prefs.syncClipboardImagesEnabled,
        syncFilesEnabled = prefs.syncClipboardFilesEnabled,
        attachmentMaxSizeMb = prefs.syncClipboardAttachmentMaxSizeMb,
        watchTreeUri = prefs.syncClipboardWatchTreeUri
    )

    private fun buildSyncClipboardStateSafely(): SyncClipboardState = try {
        buildSyncClipboardState()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to build sync clipboard state", e)
        SyncClipboardState()
    }

    fun updateSyncClipboardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                prefs.syncClipboardEnabled = enabled
                _syncClipboardState.value = _syncClipboardState.value.copy(enabled = enabled)
                if (!enabled) {
                    syncClipboardCapabilityObserverJob?.cancel()
                    _syncClipboardState.value = _syncClipboardState.value.copy(
                        detectingReceiveMode = false
                    )
                    onSyncClipboardChanged()
                } else if (_syncClipboardState.value.receiveMode != ClipboardSyncReceiveMode.OFF &&
                    prefs.syncClipboardRealtimeSupported == null
                ) {
                    requestSyncClipboardCapabilityProbe()
                } else {
                    onSyncClipboardChanged()
                }
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
                onSyncClipboardConnectionConfigChanged()
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
                onSyncClipboardConnectionConfigChanged()
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
                onSyncClipboardConnectionConfigChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update sync clipboard password", e)
            }
        }
    }

    fun updateSyncClipboardAutoReceiveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                syncClipboardCapabilityObserverJob?.cancel()
                if (!enabled) {
                    prefs.syncClipboardReceiveMode = ClipboardSyncReceiveMode.OFF
                    _syncClipboardState.value = _syncClipboardState.value.copy(
                        receiveMode = ClipboardSyncReceiveMode.OFF,
                        detectingReceiveMode = false
                    )
                    onSyncClipboardChanged()
                } else {
                    val cached = prefs.syncClipboardRealtimeSupported
                    if (cached == null) {
                        requestSyncClipboardCapabilityProbe()
                    } else {
                        val mode = if (cached) {
                            ClipboardSyncReceiveMode.REALTIME
                        } else {
                            ClipboardSyncReceiveMode.POLLING
                        }
                        prefs.syncClipboardReceiveMode = mode
                        _syncClipboardState.value = _syncClipboardState.value.copy(
                            receiveMode = mode,
                            detectingReceiveMode = false
                        )
                        onSyncClipboardChanged()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update automatic clipboard receive", e)
            }
        }
    }

    private fun onSyncClipboardConnectionConfigChanged() {
        if (_syncClipboardState.value.receiveMode != ClipboardSyncReceiveMode.OFF) {
            requestSyncClipboardCapabilityProbe()
        } else {
            onSyncClipboardChanged()
        }
    }

    private fun requestSyncClipboardCapabilityProbe() {
        syncClipboardCapabilityObserverJob?.cancel()
        prefs.syncClipboardReceiveMode = ClipboardSyncReceiveMode.POLLING
        _syncClipboardState.value = _syncClipboardState.value.copy(
            receiveMode = ClipboardSyncReceiveMode.POLLING,
            detectingReceiveMode = true
        )
        onSyncClipboardChanged()
        syncClipboardCapabilityObserverJob = viewModelScope.launch {
            while (_syncClipboardState.value.enabled &&
                _syncClipboardState.value.receiveMode != ClipboardSyncReceiveMode.OFF
            ) {
                if (prefs.syncClipboardRealtimeSupported != null) {
                    _syncClipboardState.value = _syncClipboardState.value.copy(
                        receiveMode = prefs.syncClipboardReceiveMode,
                        detectingReceiveMode = false
                    )
                    return@launch
                }
                delay(250L)
            }
        }
    }

    fun updateSyncClipboardKeepBackgroundRealtimeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                prefs.syncClipboardKeepBackgroundRealtimeEnabled = enabled
                _syncClipboardState.value =
                    _syncClipboardState.value.copy(keepBackgroundRealtimeEnabled = enabled)
                onSyncClipboardChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update keep background realtime", e)
            }
        }
    }

    fun updateSyncClipboardPullIntervalSec(intervalSec: Int) {
        viewModelScope.launch {
            try {
                val coerced = intervalSec.coerceIn(1, 600)
                prefs.syncClipboardPullIntervalSec = coerced
                _syncClipboardState.value =
                    _syncClipboardState.value.copy(pullIntervalSec = coerced)
                onSyncClipboardChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update sync clipboard pull interval", e)
            }
        }
    }

    fun updateSyncClipboardImagesEnabled(enabled: Boolean) = updateAttachmentState {
        prefs.syncClipboardImagesEnabled = enabled
        it.copy(syncImagesEnabled = enabled)
    }

    fun updateSyncClipboardFilesEnabled(enabled: Boolean) = updateAttachmentState {
        prefs.syncClipboardFilesEnabled = enabled
        it.copy(syncFilesEnabled = enabled)
    }

    fun updateSyncClipboardAttachmentMaxSizeMb(sizeMb: Int) = updateAttachmentState {
        val coerced = sizeMb.coerceIn(1, 1024)
        prefs.syncClipboardAttachmentMaxSizeMb = coerced
        it.copy(attachmentMaxSizeMb = coerced)
    }

    fun updateSyncClipboardWatchTreeUri(uri: String) = updateAttachmentState {
        prefs.syncClipboardWatchTreeUri = uri
        it.copy(watchTreeUri = uri)
    }

    private fun updateAttachmentState(
        update: (SyncClipboardState) -> SyncClipboardState
    ) {
        viewModelScope.launch {
            try {
                _syncClipboardState.value = update(_syncClipboardState.value)
                onSyncClipboardChanged()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update clipboard attachment settings", e)
            }
        }
    }

    override fun onCleared() {
        syncClipboardCapabilityObserverJob?.cancel()
        flushPendingSpeechPreset()
        super.onCleared()
    }
}
