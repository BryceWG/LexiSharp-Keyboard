package com.brycewg.asrkb.ui.floating

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floatingball.*
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 悬浮球语音识别服务（重构版）
 * 在其他输入法激活时也能通过悬浮球进行语音识别
 *
 * 重构改进：
 * 1. 使用状态机模式（FloatingBallState）替代多个布尔标志
 * 2. 职责分离到专门的管理器类：
 *    - FloatingBallViewManager: 视图和动画管理
 *    - AsrSessionManager: ASR 引擎生命周期和结果处理
 *    - FloatingBallTouchHandler: 触摸事件处理
 *    - FloatingMenuHelper: 菜单显示逻辑（与 FloatingImeSwitcherService 共享）
 * 3. 所有 catch 块都添加了日志
 */
class FloatingAsrService : Service(),
    AsrSessionManager.AsrSessionListener,
    FloatingBallTouchHandler.TouchEventListener {

    companion object {
        private const val TAG = "FloatingAsrService"
        const val ACTION_SHOW = "com.brycewg.asrkb.action.FLOATING_ASR_SHOW"
        const val ACTION_HIDE = "com.brycewg.asrkb.action.FLOATING_ASR_HIDE"
    }

    // 核心组件
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var viewManager: FloatingBallViewManager
    private lateinit var asrSessionManager: AsrSessionManager
    private lateinit var touchHandler: FloatingBallTouchHandler
    private lateinit var menuHelper: FloatingMenuHelper

    // 状态机
    private val stateMachine = FloatingBallStateMachine()

    // 协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI 状态
    private val handler = Handler(Looper.getMainLooper())
    private var imeVisible: Boolean = false
    private var currentToast: Toast? = null

    // 菜单状态
    private var radialMenuView: View? = null
    private var vendorMenuView: View? = null
    private var touchActiveGuard: Boolean = false

    // 本地模型预加载标记
    private var svPreloadTriggered: Boolean = false

    // 广播接收器
    private val hintReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE -> {
                    imeVisible = true
                    updateVisibilityByPref()
                }
                FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN -> {
                    imeVisible = false
                    updateVisibilityByPref()
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        windowManager = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)

        // 初始化管理器
        viewManager = FloatingBallViewManager(this, prefs, windowManager)
        asrSessionManager = AsrSessionManager(this, prefs, serviceScope, this)
        touchHandler = FloatingBallTouchHandler(this, prefs, viewManager, windowManager, this)
        menuHelper = FloatingMenuHelper(this, windowManager)

        // 注册广播接收器
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE)
                addAction(FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(hintReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(hintReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register hint receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, floatingAsrEnabled=${prefs.floatingAsrEnabled}")
        when (intent?.action) {
            ACTION_SHOW -> updateVisibilityByPref()
            ACTION_HIDE -> hideBall()
            FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE -> {
                imeVisible = true
                updateVisibilityByPref()
            }
            FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN -> {
                imeVisible = false
                updateVisibilityByPref()
            }
            else -> showBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        viewManager.cleanup()
        asrSessionManager.cleanup()
        touchHandler.cleanup()
        hideBall()
        hideRadialMenu()
        hideVendorMenu()

        try {
            serviceScope.cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to cancel service scope", e)
        }
        try {
            unregisterReceiver(hintReceiver)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unregister receiver", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 视图管理 ====================

    private fun showBall() {
        Log.d(TAG, "showBall called: floatingAsrEnabled=${prefs.floatingAsrEnabled}, hasOverlay=${hasOverlayPermission()}")
        if (!prefs.floatingAsrEnabled || !hasOverlayPermission()) {
            Log.w(TAG, "Cannot show ball: permission or setting issue")
            hideBall()
            return
        }

        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible && !stateMachine.isRecording) {
            Log.d(TAG, "Pref requires IME visible; hiding for now")
            hideBall()
            return
        }

        val ballView = viewManager.getBallView()
        if (ballView != null) {
            viewManager.applyBallAlpha()
            viewManager.applyBallSize()
            viewManager.updateStateVisual(stateMachine.state)
            return
        }

        // 创建触摸监听器
        val touchListener = touchHandler.createTouchListener { stateMachine.isMoveMode }

        // 显示悬浮球
        val success = viewManager.showBall(
            onClickListener = { hapticTapIfEnabled(it) },
            onTouchListener = touchListener
        )

        if (!success) {
            return
        }

        // 悬浮球首次出现时，按需异步预加载本地 SenseVoice
        tryPreloadSenseVoiceOnce()
    }

    private fun hideBall() {
        viewManager.hideBall()
    }

    private fun updateVisibilityByPref() {
        val forceVisible = (radialMenuView != null || vendorMenuView != null ||
                           stateMachine.isMoveMode || touchActiveGuard)
        if (!prefs.floatingAsrEnabled) {
            hideBall()
            return
        }
        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible &&
            !stateMachine.isRecording && !forceVisible) {
            hideBall()
            return
        }
        showBall()
    }

    // ==================== ASR 会话控制 ====================

    private fun startRecording() {
        Log.d(TAG, "startRecording called")

        // 检查权限
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "No record audio permission")
            showToast(getString(R.string.asr_error_mic_permission_denied))
            return
        }

        if (!prefs.hasAsrKeys()) {
            Log.w(TAG, "No ASR keys configured")
            showToast(getString(R.string.hint_need_keys))
            return
        }

        // 开始录音前恢复图标
        try {
            viewManager.getBallView()?.findViewById<android.widget.ImageView>(R.id.ballIcon)
                ?.setImageResource(R.drawable.ic_mic)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to reset icon to mic", e)
        }

        // 启动会话
        asrSessionManager.startRecording()
        updateVisibilityByPref()
    }

    private fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        asrSessionManager.stopRecording()
        updateVisibilityByPref()
    }

    // ==================== AsrSessionManager.AsrSessionListener ====================

    override fun onSessionStateChanged(state: FloatingBallState) {
        stateMachine.transitionTo(state)
        handler.post {
            viewManager.updateStateVisual(state)
            updateVisibilityByPref()
        }
    }

    override fun onResultCommitted(text: String, success: Boolean) {
        handler.post {
            if (success) {
                viewManager.showCompletionTick()
            }
        }
    }

    override fun onError(message: String) {
        handler.post {
            val mapped = mapErrorToFriendlyMessage(message)
            if (mapped != null) {
                showToast(mapped)
            } else {
                showToast(getString(R.string.floating_asr_error, message))
            }
        }
    }

    // ==================== FloatingBallTouchHandler.TouchEventListener ====================

    override fun onSingleTap() {
        // 移动模式：点击退出
        if (stateMachine.isMoveMode) {
            stateMachine.transitionTo(FloatingBallState.Idle)
            viewManager.getBallView()?.let {
                try {
                    viewManager.animateSnapToEdge(it)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to animate snap, falling back", e)
                    viewManager.snapToEdge(it)
                }
            }
            hideRadialMenu()
            hideVendorMenu()
            return
        }

        // 处理中：忽略点击
        if (stateMachine.isProcessing) {
            return
        }

        // 检查无障碍权限
        if (!AsrAccessibilityService.isEnabled()) {
            Log.w(TAG, "Accessibility service not enabled")
            showToast(getString(R.string.toast_need_accessibility_perm))
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to open accessibility settings", e)
            }
            return
        }

        // 切换录音状态
        if (stateMachine.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    override fun onLongPress() {
        showRadialMenu()
    }

    override fun onMoveStarted() {
        touchActiveGuard = true
    }

    override fun onMoveEnded() {
        touchActiveGuard = false
        updateVisibilityByPref()
    }

    override fun onDragCancelled() {
        touchActiveGuard = false
        updateVisibilityByPref()
    }

    // ==================== 菜单管理 ====================

    private fun showRadialMenu() {
        if (radialMenuView != null) return

        val center = viewManager.getBallCenterSnapshot()
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha", e)
            1.0f
        }

        val items = listOf(
            FloatingMenuHelper.MenuItem(
                R.drawable.ic_prompt,
                getString(R.string.label_radial_switch_prompt),
                getString(R.string.label_radial_switch_prompt)
            ) { onPickPromptPresetFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.ic_waveform,
                getString(R.string.label_radial_switch_asr),
                getString(R.string.label_radial_switch_asr)
            ) { onPickAsrVendor() },
            FloatingMenuHelper.MenuItem(
                R.drawable.ic_keyboard,
                getString(R.string.label_radial_switch_ime),
                getString(R.string.label_radial_switch_ime)
            ) { invokeImePickerFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.ic_move,
                getString(R.string.label_radial_move),
                getString(R.string.label_radial_move)
            ) { enableMoveModeFromMenu() },
            FloatingMenuHelper.MenuItem(
                if (try { prefs.postProcessEnabled } catch (_: Throwable) { false }) {
                    R.drawable.ic_star_filled
                } else {
                    R.drawable.ic_star_outline
                },
                getString(R.string.label_radial_postproc),
                getString(R.string.label_radial_postproc)
            ) { togglePostprocFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.ic_stat_upload,
                getString(R.string.label_radial_clipboard_upload),
                getString(R.string.label_radial_clipboard_upload)
            ) { uploadClipboardOnceFromMenu() },
            FloatingMenuHelper.MenuItem(
                R.drawable.ic_stat_download,
                getString(R.string.label_radial_clipboard_pull),
                getString(R.string.label_radial_clipboard_pull)
            ) { pullClipboardOnceFromMenu() }
        )

        radialMenuView = menuHelper.showRadialMenu(center, alpha, items) {
            radialMenuView = null
            updateVisibilityByPref()
        }
        updateVisibilityByPref()
    }

    private fun hideRadialMenu() {
        menuHelper.hideMenu(radialMenuView)
        radialMenuView = null
        updateVisibilityByPref()
    }

    private fun hideVendorMenu() {
        menuHelper.hideMenu(vendorMenuView)
        vendorMenuView = null
        updateVisibilityByPref()
    }

    private fun onPickPromptPresetFromMenu() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha", e)
            1.0f
        }

        val presets = try {
            prefs.getPromptPresets()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get prompt presets", e)
            emptyList()
        }
        val active = try {
            prefs.activePromptId
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get active prompt ID", e)
            ""
        }

        val entries = presets.map { p ->
            Triple(p.title, p.id == active) {
                try {
                    prefs.activePromptId = p.id
                    Toast.makeText(
                        this,
                        getString(R.string.switched_preset, p.title),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to switch prompt preset", e)
                }
                Unit
            }
        }

        vendorMenuView = menuHelper.showListPanel(
            center, alpha,
            getString(R.string.label_llm_prompt_presets),
            entries
        ) {
            vendorMenuView = null
            touchActiveGuard = false
            updateVisibilityByPref()
        }
    }

    private fun onPickAsrVendor() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha", e)
            1.0f
        }

        val vendors = listOf(
            AsrVendor.Volc to getString(R.string.vendor_volc),
            AsrVendor.SiliconFlow to getString(R.string.vendor_sf),
            AsrVendor.ElevenLabs to getString(R.string.vendor_eleven),
            AsrVendor.OpenAI to getString(R.string.vendor_openai),
            AsrVendor.DashScope to getString(R.string.vendor_dashscope),
            AsrVendor.Gemini to getString(R.string.vendor_gemini),
            AsrVendor.Soniox to getString(R.string.vendor_soniox),
            AsrVendor.SenseVoice to getString(R.string.vendor_sensevoice)
        )
        val cur = try {
            prefs.asrVendor
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get current vendor", e)
            AsrVendor.Volc
        }

        val entries = vendors.map { (v, name) ->
            Triple(name, v == cur) {
                try {
                    val old = try {
                        prefs.asrVendor
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to get old vendor", e)
                        AsrVendor.Volc
                    }
                    if (v != old) {
                        prefs.asrVendor = v
                        if (old == AsrVendor.SenseVoice && v != AsrVendor.SenseVoice) {
                            try {
                                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to unload SenseVoice", e)
                            }
                        } else if (v == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(this, prefs)
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to preload SenseVoice", e)
                            }
                        }
                    }
                    Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to switch ASR vendor", e)
                }
                Unit
            }
        }

        vendorMenuView = menuHelper.showListPanel(
            center, alpha,
            getString(R.string.label_choose_asr_vendor),
            entries
        ) {
            vendorMenuView = null
            touchActiveGuard = false
            updateVisibilityByPref()
        }
    }

    private fun invokeImePickerFromMenu() {
        hideVendorMenu()
        invokeImePicker()
    }

    private fun enableMoveModeFromMenu() {
        stateMachine.transitionTo(FloatingBallState.MoveMode)
        hideVendorMenu()
        try {
            Toast.makeText(this, getString(R.string.toast_move_mode_on), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show move mode toast", e)
        }
    }

    private fun togglePostprocFromMenu() {
        try {
            val newVal = !prefs.postProcessEnabled
            prefs.postProcessEnabled = newVal
            val msg = getString(
                R.string.status_postproc,
                if (newVal) getString(R.string.toggle_on) else getString(R.string.toggle_off)
            )
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to toggle postproc", e)
        }
    }

    private fun uploadClipboardOnceFromMenu() {
        try {
            val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(this, prefs, serviceScope)
            serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = try {
                    mgr.uploadOnce()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to upload clipboard", t)
                    false
                }
                handler.post {
                    try {
                        Toast.makeText(
                            this@FloatingAsrService,
                            getString(if (ok) R.string.sc_status_uploaded else R.string.sc_test_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to show upload result toast", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init clipboard manager for upload", e)
        }
    }

    private fun pullClipboardOnceFromMenu() {
        try {
            val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(this, prefs, serviceScope)
            serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = try {
                    mgr.pullNow(updateClipboard = true).first
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to pull clipboard", t)
                    false
                }
                handler.post {
                    try {
                        Toast.makeText(
                            this@FloatingAsrService,
                            getString(if (ok) R.string.sc_test_success else R.string.sc_test_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to show pull result toast", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init clipboard manager for pull", e)
        }
    }

    // ==================== 辅助方法 ====================

    private fun invokeImePicker() {
        try {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(SettingsActivity.EXTRA_AUTO_SHOW_IME_PICKER, true)
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to invoke IME picker", e)
            try {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Throwable) {
                Log.e(TAG, "Failed to open IME settings", e2)
            }
        }
    }

    private fun isOurImeEnabled(imm: android.view.inputmethod.InputMethodManager?): Boolean {
        val list = try {
            imm?.enabledInputMethodList
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get enabled IME list", e)
            null
        }
        if (list?.any { it.packageName == packageName } == true) return true
        return try {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            val id = "$packageName/.ime.AsrKeyboardService"
            enabled?.contains(id) == true || (enabled?.split(':')?.any { it.startsWith(packageName) } == true)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check IME enabled via settings", e)
            false
        }
    }

    private fun showToast(message: String) {
        handler.post {
            try {
                currentToast?.cancel()
                val ctx = try {
                    LocaleHelper.wrap(this@FloatingAsrService)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to wrap context for toast", e)
                    this@FloatingAsrService
                }
                currentToast = Toast.makeText(ctx, message, Toast.LENGTH_SHORT)
                currentToast?.show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show toast: $message", e)
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (prefs.micHapticEnabled) {
                view?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to perform haptic feedback", e)
        }
    }

    private fun tryPreloadSenseVoiceOnce() {
        try {
            if (!svPreloadTriggered) {
                if (prefs.asrVendor == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                    val prepared = try {
                        com.brycewg.asrkb.asr.isSenseVoicePrepared()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to check SenseVoice preparation", e)
                        false
                    }
                    if (!prepared) {
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                            try {
                                com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                                    this@FloatingAsrService,
                                    prefs
                                )
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to preload SenseVoice", e)
                            }
                        }
                    }
                }
                svPreloadTriggered = true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to trigger SenseVoice preload", e)
        }
    }

    private fun mapErrorToFriendlyMessage(raw: String): String? {
        if (raw.isEmpty()) return null
        val lower = raw.lowercase(Locale.ROOT)

        // 空结果
        try {
            val emptyHints = listOf(
                getString(R.string.error_asr_empty_result),
                getString(R.string.error_audio_empty),
                "empty asr result",
                "empty audio",
                "识别返回为空",
                "空音频"
            )
            if (emptyHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_empty_result)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check empty hints", e)
        }

        // HTTP 状态码
        try {
            val httpCode = Regex("HTTP\\s+(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            when (httpCode) {
                401 -> return getString(R.string.asr_error_auth_invalid)
                403 -> return getString(R.string.asr_error_auth_forbidden)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse HTTP code", e)
        }

        // WebSocket code
        try {
            val code = Regex("(?:ASR\\s*Error|status|code)\\s*(\\d{3})")
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            when (code) {
                401 -> return getString(R.string.asr_error_auth_invalid)
                403 -> return getString(R.string.asr_error_auth_forbidden)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse WebSocket code", e)
        }

        // 录音权限
        try {
            val permHints = listOf(
                getString(R.string.error_record_permission_denied),
                getString(R.string.hint_need_permission),
                "record audio permission"
            )
            if (permHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_mic_permission_denied)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check permission hints", e)
        }

        // 麦克风被占用
        try {
            val micBusyHints = listOf(
                getString(R.string.error_audio_init_failed),
                "audio recorder busy",
                "resource busy",
                "in use",
                "device busy"
            )
            if (micBusyHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_mic_in_use)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check mic busy hints", e)
        }

        // SSL/TLS 握手失败
        if (lower.contains("handshake") || lower.contains("sslhandshakeexception") ||
            lower.contains("trust anchor") || lower.contains("certificate")
        ) {
            return getString(R.string.asr_error_network_handshake)
        }

        // 网络不可用
        if (lower.contains("unable to resolve host") ||
            lower.contains("no address associated") ||
            lower.contains("failed to connect") ||
            lower.contains("connect exception") ||
            lower.contains("network is unreachable") ||
            lower.contains("software caused connection abort") ||
            lower.contains("timeout") ||
            lower.contains("timed out")
        ) {
            return getString(R.string.asr_error_network_unavailable)
        }

        return null
    }
}
