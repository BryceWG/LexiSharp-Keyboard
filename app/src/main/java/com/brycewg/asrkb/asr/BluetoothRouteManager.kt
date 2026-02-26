package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 蓝牙/耳机音频路由预热管理器
 *
 * 策略：
 * - 当键盘可见或存在编辑焦点时，尝试提前将“通信设备”路由到耳机（蓝牙/有线）。
 * - 当开始录音时保持路由；录音结束后若键盘不可见则断开。
 * - 用户未开启“耳机麦克风优先”或无可用耳机时不进行任何操作。
 * - API 31+ 优先使用 setCommunicationDevice；低版本使用经典 SCO（start/stopBluetoothSco）。
 */
object BluetoothRouteManager {
    private const val TAG = "BluetoothRouteManager"

    private var appContext: Context? = null
    private var prefs: Prefs? = null

    // 状态
    @Volatile private var imeSceneActive: Boolean = false

    @Volatile private var recordingCount: Int = 0

    // 当前路由句柄
    @Volatile private var commDeviceSet: Boolean = false

    @Volatile private var commListener: Any? = null

    @Volatile private var selectedDevice: AudioDeviceInfo? = null

    @Volatile private var scoStarted: Boolean = false

    @Volatile private var audioModeChanged: Boolean = false

    @Volatile private var previousAudioMode: Int? = null

    @Volatile private var pendingConnectStartAtMs: Long = 0L

    @Volatile private var lastAutoReconnectAtMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initIfNeeded(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            prefs = Prefs(context.applicationContext)
        }
    }

    fun setImeActive(context: Context, active: Boolean) {
        initIfNeeded(context)
        imeSceneActive = active
        updateRouteAsync("ime_${if (active) "on" else "off"}")
    }

    fun onRecordingStarted(context: Context) {
        initIfNeeded(context)
        recordingCount = (recordingCount + 1).coerceAtLeast(1)
        updateRouteAsync("rec_start")
    }

    fun onRecordingStopped(context: Context) {
        initIfNeeded(context)
        recordingCount = (recordingCount - 1).coerceAtLeast(0)
        updateRouteAsync("rec_stop")
    }

    fun cleanup() {
        try {
            scope.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cleanup cancel scope", t)
        }
        // 尝试断开路由
        try {
            disconnectInternal("cleanup")
        } catch (t: Throwable) {
            Log.w(TAG, "cleanup disconnect", t)
        }
    }

    private fun shouldRoute(): Boolean {
        val p = prefs ?: return false
        if (!p.headsetMicPriorityEnabled) return false
        return recordingCount > 0 || imeSceneActive
    }

    private fun updateRouteAsync(reason: String) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                if (shouldRoute()) {
                    connectInternal(ctx, reason)
                } else {
                    disconnectInternal(reason)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "updateRouteAsync($reason) failed", t)
            }
        }
    }

    private fun connectInternal(context: Context, reason: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 已连接则跳过
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (commDeviceSet && selectedDevice != null) return
        } else {
            if (scoStarted) return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tryModernConnect(am)
        } else {
            tryLegacyScoConnect(am)
        }
    }

    @Suppress("DEPRECATION")
    private fun disconnectInternal(reason: String) {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (commDeviceSet) {
                try {
                    clearCommunicationDeviceSafely(am, commListener)
                } catch (t: Throwable) {
                    Log.w(TAG, "clearCommunicationDeviceSafely", t)
                }
                commDeviceSet = false
                selectedDevice = null
                commListener = null
            }
        }
        if (scoStarted) {
            try {
                am.stopBluetoothSco()
            } catch (t: Throwable) {
                Log.w(TAG, "stopBluetoothSco", t)
            }
            scoStarted = false
        }
        if (audioModeChanged) {
            try {
                if (previousAudioMode != null) am.mode = previousAudioMode!!
            } catch (t: Throwable) {
                Log.w(TAG, "restore audio mode", t)
            }
            previousAudioMode = null
            audioModeChanged = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun tryModernConnect(am: AudioManager) {
        val candidates = try {
            am.getAvailableCommunicationDevices()
        } catch (se: SecurityException) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted when listing comm devices", se)
            emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "getAvailableCommunicationDevices failed", t)
            emptyList()
        }
        if (candidates.isEmpty()) return

        val target = candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        if (target == null) return

        pendingConnectStartAtMs = SystemClock.elapsedRealtime()
        val ok = try {
            am.setCommunicationDevice(target)
        } catch (t: Throwable) {
            Log.w(TAG, "setCommunicationDevice failed", t)
            false
        }
        if (!ok) return

        selectedDevice = target
        commDeviceSet = true
        // 轻量监听：遇到系统切走时可感知（不强制等待）
        val exec = java.util.concurrent.Executor { r ->
            try {
                r.run()
            } catch (t: Throwable) {
                Log.w(TAG, "CommDeviceChanged runnable error", t)
            }
        }
        val l = AudioManager.OnCommunicationDeviceChangedListener { dev ->
            val sel = selectedDevice
            if (dev == null) return@OnCommunicationDeviceChangedListener
            if (sel != null && dev.id == sel.id) {
                // 完成切换，记录耗时
                val dt = (SystemClock.elapsedRealtime() - pendingConnectStartAtMs).coerceAtLeast(0)
                if (dt > 0) Log.i(TAG, "Communication device ready in ${dt}ms (id=${dev.id})")
                return@OnCommunicationDeviceChangedListener
            }
            // 被系统切走；若仍处于应当路由的场景，尝试轻量重连
            Log.i(TAG, "Communication device changed by system (id=${dev.id})")
            if (shouldRoute()) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAutoReconnectAtMs >= 500) {
                    lastAutoReconnectAtMs = now
                    try {
                        // 重新选择目标并设置
                        val devices = try {
                            am.getAvailableCommunicationDevices()
                        } catch (_: Throwable) {
                            emptyList()
                        }
                        val desired = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                        if (desired != null && desired.id != dev.id) {
                            pendingConnectStartAtMs = SystemClock.elapsedRealtime()
                            val ok2 = try {
                                am.setCommunicationDevice(desired)
                            } catch (t: Throwable) {
                                Log.w(TAG, "auto-reconnect setCommunicationDevice failed", t)
                                false
                            }
                            if (ok2) {
                                selectedDevice = desired
                                commDeviceSet = true
                                Log.i(TAG, "Auto-reconnected communication device (id=${desired.id})")
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "auto-reconnect error", t)
                    }
                }
            }
        }
        commListener = l
        try {
            am.addOnCommunicationDeviceChangedListener(exec, l)
        } catch (t: Throwable) {
            Log.w(TAG, "addOnCommunicationDeviceChangedListener", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun tryLegacyScoConnect(am: AudioManager) {
        // 仅当经典蓝牙可用时尝试；BLE 不涉及 SCO
        if (!am.isBluetoothScoAvailableOffCall) return
        // 切到通信模式可提升部分机型的稳定性
        previousAudioMode = am.mode
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            audioModeChanged = true
        } catch (t: Throwable) {
            Log.w(TAG, "set MODE_IN_COMMUNICATION", t)
        }

        try {
            if (!am.isBluetoothScoOn) am.startBluetoothSco()
        } catch (t: Throwable) {
            Log.w(TAG, "startBluetoothSco", t)
        }
        scoStarted = true // 预热路径不等待 CONNECTED；真正采集前会再次确认
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun clearCommunicationDeviceSafely(audioManager: AudioManager, listenerToken: Any?) {
        if (listenerToken is AudioManager.OnCommunicationDeviceChangedListener) {
            try {
                audioManager.removeOnCommunicationDeviceChangedListener(listenerToken)
            } catch (t: Throwable) {
                Log.w(TAG, "removeOnCommunicationDeviceChangedListener", t)
            }
        }
        try {
            audioManager.clearCommunicationDevice()
        } catch (t: Throwable) {
            Log.w(TAG, "clearCommunicationDevice", t)
        }
    }

    private fun requireNotNullContext(): Context {
        return appContext ?: throw IllegalStateException("BluetoothRouteManager not initialized")
    }
}
