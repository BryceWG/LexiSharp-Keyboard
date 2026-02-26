package com.brycewg.asrkb.ui.floating

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.floatingball.AsrSessionManager
import com.brycewg.asrkb.ui.floatingball.FloatingBallStateMachine
import com.brycewg.asrkb.ui.floatingball.FloatingBallTouchHandler
import com.brycewg.asrkb.ui.floatingball.FloatingBallViewManager
import com.brycewg.asrkb.ui.floatingball.FloatingMenuHelper
import com.brycewg.asrkb.util.HapticFeedbackHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮球语音识别服务
 *
 * 组件装配：
 * - [FloatingBallViewManager]：WindowManager 视图与动画
 * - [AsrSessionManager]：录音/识别会话
 * - [FloatingBallTouchHandler]：触摸手势识别
 * - [FloatingAsrInteractionController]：交互与菜单动作编排
 * - [FloatingVisibilityCoordinator]：可见性策略
 */
class FloatingAsrService : Service() {
    companion object {
        private const val TAG = "FloatingAsrService"

        const val ACTION_SHOW = "com.brycewg.asrkb.action.FLOATING_ASR_SHOW"
        const val ACTION_HIDE = "com.brycewg.asrkb.action.FLOATING_ASR_HIDE"
        const val ACTION_RESET_POSITION = "com.brycewg.asrkb.action.FLOATING_ASR_RESET_POS"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var viewManager: FloatingBallViewManager
    private lateinit var asrSessionManager: AsrSessionManager
    private lateinit var touchHandler: FloatingBallTouchHandler
    private lateinit var visibilityCoordinator: FloatingVisibilityCoordinator
    private lateinit var overlayPermissionGate: OverlayPermissionGate
    private lateinit var notifier: UserNotifier
    private lateinit var interactionController: FloatingAsrInteractionController

    private val stateMachine = FloatingBallStateMachine()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var imeVisible: Boolean = false
    private var localPreloadTriggered: Boolean = false

    private val hintReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingImeHints.ACTION_HINT_IME_VISIBLE -> {
                    imeVisible = true
                    DebugLogManager.log("float", "hint", mapOf("action" to "VISIBLE"))
                    visibilityCoordinator.applyVisibility("hint_visible")
                    try {
                        BluetoothRouteManager.setImeActive(this@FloatingAsrService, true)
                    } catch (t: Throwable) {
                        Log.w(TAG, "BluetoothRouteManager setImeActive(true)", t)
                    }
                }
                FloatingImeHints.ACTION_HINT_IME_HIDDEN -> {
                    imeVisible = false
                    DebugLogManager.log("float", "hint", mapOf("action" to "HIDDEN"))
                    visibilityCoordinator.applyVisibility("hint_hidden")
                    try {
                        BluetoothRouteManager.setImeActive(this@FloatingAsrService, false)
                    } catch (t: Throwable) {
                        Log.w(TAG, "BluetoothRouteManager setImeActive(false)", t)
                    }
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
        notifier = UserNotifier(this, handler, TAG)
        overlayPermissionGate = OverlayPermissionGate(this, notifier, TAG)

        viewManager = FloatingBallViewManager(this, prefs, windowManager)

        val menuHelper = FloatingMenuHelper(this, windowManager)
        val menuController = FloatingMenuController(menuHelper)
        interactionController = FloatingAsrInteractionController(
            context = this,
            prefs = prefs,
            viewManager = viewManager,
            menuController = menuController,
            stateMachine = stateMachine,
            notifier = notifier,
            scope = serviceScope,
            tag = TAG,
            isImeVisible = { imeVisible },
        )
        asrSessionManager = AsrSessionManager(this, prefs, serviceScope, interactionController)
        interactionController.asrSessionManager = asrSessionManager
        touchHandler = FloatingBallTouchHandler(this, prefs, viewManager, windowManager, interactionController)

        visibilityCoordinator = FloatingVisibilityCoordinator(
            prefs = prefs,
            stateMachine = stateMachine,
            viewManager = viewManager,
            tag = TAG,
            hasOverlayPermission = { overlayPermissionGate.hasPermission() },
            isImeVisible = { imeVisible },
            isForceVisibleActive = { interactionController.isForceVisibleActive() },
            showBall = { src -> showBall(src) },
            hideBall = { hideBall() },
        )
        interactionController.applyVisibility = { src -> visibilityCoordinator.applyVisibility(src) }

        try {
            val filter = android.content.IntentFilter().apply {
                addAction(FloatingImeHints.ACTION_HINT_IME_VISIBLE)
                addAction(FloatingImeHints.ACTION_HINT_IME_HIDDEN)
            }
            ContextCompat.registerReceiver(
                /* context = */
                this,
                /* receiver = */
                hintReceiver,
                /* filter = */
                filter,
                /* flags = */
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register hint receiver", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::viewManager.isInitialized) return
        try {
            viewManager.remapPositionForCurrentDisplay("service_onConfigurationChanged:${newConfig.orientation}")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to remap floating ball position on configuration change", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enabled = try {
            prefs.floatingAsrEnabled
        } catch (_: Throwable) {
            false
        }
        Log.d(TAG, "onStartCommand: action=${intent?.action}, floatingAsrEnabled=$enabled")

        when (intent?.action) {
            ACTION_SHOW -> {
                if (enabled && !overlayPermissionGate.hasPermission()) {
                    overlayPermissionGate.showMissingPermissionToast()
                }
                visibilityCoordinator.applyVisibility("start_action_show")
            }
            ACTION_HIDE -> hideBall()
            ACTION_RESET_POSITION -> handleResetBallPosition()
            FloatingImeHints.ACTION_HINT_IME_VISIBLE -> {
                imeVisible = true
                DebugLogManager.log("float", "hint", mapOf("action" to "VISIBLE"))
                visibilityCoordinator.applyVisibility("start_hint_visible")
                try {
                    BluetoothRouteManager.setImeActive(this, true)
                } catch (t: Throwable) {
                    Log.w(TAG, "BluetoothRouteManager setImeActive(true)", t)
                }
            }
            FloatingImeHints.ACTION_HINT_IME_HIDDEN -> {
                imeVisible = false
                DebugLogManager.log("float", "hint", mapOf("action" to "HIDDEN"))
                visibilityCoordinator.applyVisibility("start_hint_hidden")
                try {
                    BluetoothRouteManager.setImeActive(this, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "BluetoothRouteManager setImeActive(false)", t)
                }
            }
            else -> visibilityCoordinator.applyVisibility("start_default")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        try {
            if (::interactionController.isInitialized) interactionController.cleanup()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cleanup interaction controller", e)
        }
        try {
            if (::notifier.isInitialized) notifier.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel notifier", e)
        }

        hideBall()
        viewManager.cleanup()
        asrSessionManager.cleanup()
        touchHandler.cleanup()

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

    private fun showBall(src: String = "update_visibility") {
        Log.d(TAG, "showBall called: src=$src")

        if (viewManager.getBallView() != null) {
            viewManager.applyBallAlpha()
            viewManager.applyBallSize()
            viewManager.updateStateVisual(stateMachine.state)
            return
        }

        val touchListener = touchHandler.createTouchListener { stateMachine.isMoveMode }
        val success = viewManager.showBall(
            onClickListener = { hapticTapIfEnabled(it) },
            onTouchListener = touchListener,
            initialState = stateMachine.state,
        )
        if (!success) {
            DebugLogManager.log("float", "show_failed")
            return
        }
        DebugLogManager.log("float", "show_success")

        tryPreloadLocalAsrOnce()
    }

    private fun hideBall() {
        viewManager.hideBall()
        DebugLogManager.log("float", "hide")
    }

    private fun handleResetBallPosition() {
        try {
            prefs.floatingBallPosX = -1
            prefs.floatingBallPosY = -1
            prefs.floatingBallDockSide = 0
            prefs.floatingBallDockFraction = -1f
            prefs.floatingBallDockHidden = false
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to reset saved position in prefs", e)
        }

        val hasView = viewManager.getBallView() != null
        if (hasView) {
            try {
                viewManager.resetPositionToDefault()
                if (!prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible) {
                    viewManager.animateHideToEdgePartialIfNeeded()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to apply default position reset on existing view", e)
            }
        } else {
            visibilityCoordinator.applyVisibility("reset_pos_no_view")
        }
    }

    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(this, prefs, view)
    }

    private fun tryPreloadLocalAsrOnce() {
        if (localPreloadTriggered) return

        val enabled = when (prefs.asrVendor) {
            AsrVendor.SenseVoice -> prefs.svPreloadEnabled
            AsrVendor.FunAsrNano -> prefs.fnPreloadEnabled
            AsrVendor.Telespeech -> prefs.tsPreloadEnabled
            AsrVendor.Paraformer -> prefs.pfPreloadEnabled
            else -> false
        }
        if (!enabled) return
        if (com.brycewg.asrkb.asr.isLocalAsrPrepared(prefs)) {
            localPreloadTriggered = true
            return
        }

        localPreloadTriggered = true
        serviceScope.launch(Dispatchers.Default) {
            com.brycewg.asrkb.asr.preloadLocalAsrIfConfigured(this@FloatingAsrService, prefs)
        }
    }
}
