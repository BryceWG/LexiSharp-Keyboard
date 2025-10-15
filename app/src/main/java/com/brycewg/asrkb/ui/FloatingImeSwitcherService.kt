package com.brycewg.asrkb.ui

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

/**
 * 悬浮球：当当前输入法不是本应用 IME 时显示，点击快速呼出系统输入法选择器，
 * 方便用户切换到 ASR 键盘。
 */
class FloatingImeSwitcherService : Service() {

    companion object {
        const val ACTION_SHOW = "com.brycewg.asrkb.action.FLOATING_SHOW"
        const val ACTION_HIDE = "com.brycewg.asrkb.action.FLOATING_HIDE"
        const val ACTION_HINT_IME_VISIBLE = "com.brycewg.asrkb.action.IME_VISIBLE"
        const val ACTION_HINT_IME_HIDDEN = "com.brycewg.asrkb.action.IME_HIDDEN"
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var imeVisible: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val settingsObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            updateBallVisibility()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        // 监听系统当前输入法变化
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                settingsObserver
            )
        } catch (_: Throwable) { }
        // 监听无障碍服务发来的“IME显示/隐藏”提示
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(ACTION_HINT_IME_VISIBLE)
                addAction(ACTION_HINT_IME_HIDDEN)
            }
            registerReceiver(hintReceiver, filter)
        } catch (_: Throwable) { }
        updateBallVisibility()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> updateBallVisibility()
            ACTION_HIDE -> removeBall()
            ACTION_HINT_IME_VISIBLE -> {
                imeVisible = true
                updateBallVisibility()
            }
            ACTION_HINT_IME_HIDDEN -> {
                imeVisible = false
                updateBallVisibility()
            }
            else -> updateBallVisibility()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { contentResolver.unregisterContentObserver(settingsObserver) } catch (_: Throwable) { }
        try { unregisterReceiver(hintReceiver) } catch (_: Throwable) { }
        removeBall()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun updateBallVisibility() {
        val prefs = Prefs(this)
        if (!prefs.floatingSwitcherEnabled || !hasOverlayPermission()) {
            removeBall()
            return
        }

        // 如果启用了悬浮球语音识别模式,则不显示切换输入法的悬浮球
        if (prefs.floatingAsrEnabled) {
            removeBall()
            return
        }

        // 开启“仅在输入法面板显示时显示”，但当前未检测到输入场景 -> 隐藏
        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible) {
            removeBall()
            return
        }

        if (isOurImeCurrent()) {
            removeBall()
            return
        }
        ensureBall()
        applyBallSize()
        applyBallAlpha()
    }

    private val hintReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_HINT_IME_VISIBLE -> {
                    imeVisible = true
                    updateBallVisibility()
                }
                ACTION_HINT_IME_HIDDEN -> {
                    imeVisible = false
                    updateBallVisibility()
                }
            }
        }
    }

    private fun ensureBall() {
        if (ballView != null) return
        val iv = ImageView(this)
        iv.setImageResource(R.drawable.logo)
        iv.clearColorFilter()
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iv.background = ContextCompat.getDrawable(this, R.drawable.bg_floating_ball)
        val pad = dp(6)
        iv.setPadding(pad, pad, pad, pad)
        iv.contentDescription = getString(R.string.cd_floating_switcher)
        iv.setOnClickListener { onBallClick() }
        attachDrag(iv)

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val size = try { Prefs(this).floatingBallSizeDp } catch (_: Throwable) { 28 }
        val params = WindowManager.LayoutParams(
            dp(size), dp(size),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // 恢复上次位置（若存在），否则使用默认值
        try {
            val prefs = Prefs(this)
            val sx = prefs.floatingBallPosX
            val sy = prefs.floatingBallPosY
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val vw = params.width
            val vh = params.height
            if (sx >= 0 && sy >= 0) {
                params.x = sx.coerceIn(0, (screenW - vw).coerceAtLeast(0))
                params.y = sy.coerceIn(0, (screenH - vh).coerceAtLeast(0))
            } else {
                params.x = dp(12)
                params.y = dp(180)
            }
        } catch (_: Throwable) {
            params.x = dp(12)
            params.y = dp(180)
        }

        try {
            windowManager.addView(iv, params)
            ballView = iv
            lp = params
        } catch (_: Throwable) { }
        applyBallAlpha()
    }

    private fun removeBall() {
        val v = ballView ?: return
        try { windowManager.removeView(v) } catch (_: Throwable) { }
        ballView = null
        lp = null
    }

    private fun applyBallAlpha() {
        val a = try { Prefs(this).floatingSwitcherAlpha } catch (_: Throwable) { 1.0f }
        val v = ballView
        if (v != null) {
            try { v.alpha = a } catch (_: Throwable) { }
        }
    }

    private fun applyBallSize() {
        val v = ballView ?: return
        val p = lp ?: return
        val size = try { Prefs(this).floatingBallSizeDp } catch (_: Throwable) { 28 }
        p.width = dp(size)
        p.height = dp(size)
        try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
    }

    private fun onBallClick() {
        // 目标：尽量一键切到我们的键盘；若不可行，唤起系统输入法选择器
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                // 未启用 -> 打开系统设置页让用户启用
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
                return
            }
            // 从后台直接调 showInputMethodPicker 可能被系统忽略，改为启动透明 Activity 置前后再调
            val intent = Intent(this, ImePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
            } catch (_: Throwable) { }
        }
    }

    private fun isOurImeEnabled(imm: InputMethodManager?): Boolean {
        val list = try { imm?.enabledInputMethodList } catch (_: Throwable) { null }
        if (list?.any { it.packageName == packageName } == true) return true
        return try {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            val id = "$packageName/.ime.AsrKeyboardService"
            enabled?.contains(id) == true || (enabled?.split(':')?.any { it.startsWith(packageName) } == true)
        } catch (_: Throwable) { false }
    }

    private fun isOurImeCurrent(): Boolean {
        return try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val expectedId = "$packageName/.ime.AsrKeyboardService"
            current == expectedId
        } catch (_: Throwable) {
            false
        }
    }

    private var edgeAnimator: ValueAnimator? = null

    private fun attachDrag(target: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var isDragging = false
        val touchSlop = dp(4)
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var longPressPosted = false
        val longPressRunnable = Runnable {
            isDragging = true
            longPressPosted = false
        }

        target.setOnTouchListener { v, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 停止吸附动画，准备新的拖动
                    edgeAnimator?.cancel()
                    moved = false
                    isDragging = false
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    if (!longPressPosted) {
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                        longPressPosted = true
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) moved = true

                    if (!isDragging) {
                        // 若在长按触发前移动过远，则取消长按判定
                        if (moved && longPressPosted) {
                            handler.removeCallbacks(longPressRunnable)
                            longPressPosted = false
                        }
                        return@setOnTouchListener true
                    }

                    // 拖动中：更新位置并限制在屏幕范围内
                    val dm = resources.displayMetrics
                    val screenW = dm.widthPixels
                    val screenH = dm.heightPixels
                    val vw = if (v.width > 0) v.width else p.width
                    val vh = if (v.height > 0) v.height else p.height
                    val nx = (startX + dx).coerceIn(0, screenW - vw)
                    val ny = (startY + dy).coerceIn(0, screenH - vh)
                    p.x = nx
                    p.y = ny
                    try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressPosted) {
                        handler.removeCallbacks(longPressRunnable)
                        longPressPosted = false
                    }
                    if (!isDragging && !moved) {
                        v.performClick()
                    } else if (isDragging) {
                        // 拖动结束后自动吸附到最近的屏幕左右边缘，并限制在屏幕可视范围内（带动画）
                        try { animateSnapToEdge(v) } catch (_: Throwable) { snapToEdge(v) }
                    }
                    moved = false
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(v: View) {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try { Prefs(this).floatingBallSizeDp } catch (_: Throwable) { 28 }
        val vw = if (v.width > 0) v.width else dp(def)
        val vh = if (v.height > 0) v.height else dp(def)
        val margin = dp(0)

        // 计算目标X：吸附到左或右
        val centerX = p.x + vw / 2
        val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
        // 约束Y范围，避免越界
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val targetY = p.y.coerceIn(minY, maxY)

        p.x = targetX
        p.y = targetY
        try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
        persistBallPosition()
    }

    private fun animateSnapToEdge(v: View) {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try { Prefs(this).floatingBallSizeDp } catch (_: Throwable) { 28 }
        val vw = if (v.width > 0) v.width else dp(def)
        val vh = if (v.height > 0) v.height else dp(def)
        val margin = dp(0)

        val centerX = p.x + vw / 2
        val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val targetY = p.y.coerceIn(minY, maxY)

        val startX = p.x
        val startY = p.y
        val dx = targetX - startX
        val dy = targetY - startY

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                p.x = (startX + dx * f).toInt()
                p.y = (startY + dy * f).toInt()
                try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                }
            })
            start()
        }
    }

    private fun persistBallPosition() {
        val p = lp ?: return
        try {
            val prefs = Prefs(this)
            prefs.floatingBallPosX = p.x
            prefs.floatingBallPosY = p.y
        } catch (_: Throwable) { }
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }

}
