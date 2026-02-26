package com.brycewg.asrkb.ui.floatingball

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.UiColors
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.widgets.ProcessingSpinnerView
import com.google.android.material.color.DynamicColors
import kotlin.math.sqrt

/**
 * 悬浮球视图管理器
 * 负责管理 WindowManager、视图创建、位置管理和动画
 */
class FloatingBallViewManager(
    private val context: Context,
    private val prefs: Prefs,
    private val windowManager: WindowManager,
) {
    companion object {
        private const val TAG = "FloatingBallViewManager"

        private const val RIPPLE_ORIGIN_X_FRACTION = 0.55f
        private const val RIPPLE_ORIGIN_Y_FRACTION = 0.45f
        private const val RIPPLE_CLIP_INSET_FRACTION = 0.06f
        private const val RIPPLE_START_SCALE = 0.10f
        private const val RIPPLE_MAX_SCALE_EXTRA = 0.02f
        private const val EDGE_HANDLE_SIZE_DP = 24
    }

    private var ballView: View? = null
    private var ballIcon: ImageView? = null
    private var edgeHandleIcon: ImageView? = null
    private var processingSpinner: ProcessingSpinnerView? = null
    private var rippleClip: View? = null
    private var ripple1: View? = null
    private var ripple2: View? = null
    private var ripple3: View? = null
    private var lp: WindowManager.LayoutParams? = null

    // 动画

    private var rippleAnimators: MutableList<Animator> = mutableListOf()
    private var edgeAnimator: ValueAnimator? = null
    private var edgeHandleVisible: Boolean = false
    private var errorVisualActive: Boolean = false
    private var errorShakeAnimator: ValueAnimator? = null
    private var errorClearRunnable: Runnable? = null
    private var recordingBreathAnimator: ValueAnimator? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var completionResetPosted: Boolean = false
    private var monetContext: Context? = null
    private var currentState: FloatingBallState = FloatingBallState.Idle

    // 贴边半隐时仅显示“箭头把手”的宽度（需与布局一致）

    // 记录“旋转前”的贴边锚点，用于横竖屏切换时的位置映射
    private var cachedDockAnchor: DockAnchor? = null

    /** 获取悬浮球视图 */
    fun getBallView(): View? = ballView

    /** 获取布局参数 */
    fun getLayoutParams(): WindowManager.LayoutParams? = lp

    /** 显示悬浮球 */
    fun showBall(onClickListener: (View) -> Unit, onTouchListener: View.OnTouchListener, initialState: FloatingBallState): Boolean {
        if (ballView != null) {
            applyBallAlpha()
            applyBallSize()
            try {
                updateStateVisual(currentState)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to refresh state on existing view", e)
            }
            return true
        }

        try {
            val themedCtx = ContextThemeWrapper(context, R.style.Theme_ASRKeyboard)
            val dynCtx = DynamicColors.wrapContextIfAvailable(themedCtx)
            monetContext = dynCtx

            val view = LayoutInflater.from(dynCtx).inflate(R.layout.floating_asr_ball, null, false)
            ballIcon = view.findViewById(R.id.ballIcon)
            edgeHandleIcon = view.findViewById(R.id.edgeHandleIcon)
            rippleClip = view.findViewById(R.id.rippleClip)
            ripple1 = view.findViewById(R.id.ripple1)
            ripple2 = view.findViewById(R.id.ripple2)
            ripple3 = view.findViewById(R.id.ripple3)
            val ballContainer = try {
                view.findViewById<android.widget.FrameLayout>(R.id.ballContainer)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to find ballContainer", e)
                null
            }

            // 获取 Monet 动态颜色
            val colorSecondaryContainer = getMonetColor(
                com.google.android.material.R.attr.colorSecondaryContainer,
                0xFF6200EE.toInt(),
            )
            val colorSecondary = getMonetColor(
                com.google.android.material.R.attr.colorSecondary,
                colorSecondaryContainer,
            )

            // 将相对更重的初始化（波纹背景/自定义进度指示器）延后到下一帧，
            // 以降低 addView 当帧的主线程压力，避免与 IME 显示竞争导致掉帧。
            view.post {
                try {
                    setupRippleBackgrounds(colorSecondary)
                } catch (e: Throwable) {
                    Log.w(TAG, "Deferred ripple setup failed", e)
                }
                try {
                    setupProcessingSpinner(ballContainer, colorSecondary)
                } catch (e: Throwable) {
                    Log.w(TAG, "Deferred spinner setup failed", e)
                }
                // 延后初始化完成后，根据当前状态刷新一次，以确保 Processing 时能立刻显示动画
                try {
                    updateStateVisual(currentState)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to apply state after deferred init", e)
                }
            }

            // 绑定点击和拖动监听
            ballIcon?.setOnClickListener(onClickListener)
            view.setOnTouchListener(onTouchListener)
            ballIcon?.setOnTouchListener(onTouchListener)
            edgeHandleIcon?.setOnTouchListener(onTouchListener)

            // 创建 WindowManager.LayoutParams
            val params = createWindowLayoutParams()

            // 添加视图
            windowManager.addView(view, params)
            ballView = view
            lp = params
            applyBallAlpha()
            applyBallSize()
            // 应用初始状态
            try {
                updateStateVisual(initialState)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to apply initial state", e)
            }
            Log.d(TAG, "Ball view added successfully")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add ball view", e)
            return false
        }
    }

    /** 隐藏悬浮球 */
    fun hideBall() {
        val v = ballView ?: return
        // 在移除视图前停止动画
        try {
            cleanup()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cleanup before hide", e)
        }
        try {
            persistBallPosition()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist ball position", e)
        }
        try {
            windowManager.removeView(v)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to remove ball view", e)
        }
        // 释放所有与旧视图树绑定的引用，确保下次 show 时重新创建/挂载
        ballView = null
        ballIcon = null
        edgeHandleIcon = null
        processingSpinner = null
        ripple1 = null
        ripple2 = null
        ripple3 = null
        rippleClip = null
        edgeHandleVisible = false
        lp = null
    }

    fun isEdgeHandleVisible(): Boolean = edgeHandleVisible

    /** 应用悬浮球透明度 */
    fun applyBallAlpha() {
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha, using default", e)
            1.0f
        }
        ballView?.alpha = alpha
    }

    /** 应用悬浮球大小 */
    fun applyBallSize() {
        val v = ballView ?: return
        val p = lp ?: return
        val size = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get size, using default", e)
            56
        }
        p.width = dp(size)
        p.height = dp(size)
        try {
            windowManager.updateViewLayout(v, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update view layout", e)
        }

        // 同步调整内部图标大小，保持随悬浮球缩放
        try {
            updateBallIconSize()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update ball icon size", e)
        }

        v.post {
            applyRippleClipOutline()
            applyRippleOriginOffset()
        }
    }

    /** 根据悬浮球窗口大小按比例调整麦克风图标尺寸 */
    private fun updateBallIconSize() {
        val icon = ballIcon ?: return
        val p = lp ?: return
        val ballSidePx = listOf(p.width, p.height).filter { it > 0 }.minOrNull() ?: return
        val target = ballSidePx
        val lpIcon = icon.layoutParams ?: return
        if (lpIcon.width != target || lpIcon.height != target) {
            lpIcon.width = target
            lpIcon.height = target
            icon.layoutParams = lpIcon
        }
    }

    /** 更新悬浮球状态显示 */
    fun updateStateVisual(state: FloatingBallState) {
        val prevState = currentState
        val enteringError = state is FloatingBallState.Error &&
            (prevState !is FloatingBallState.Error || prevState.message != state.message)
        val leavingError = prevState is FloatingBallState.Error && state !is FloatingBallState.Error

        currentState = state
        if (leavingError) {
            cancelErrorVisual()
        }
        // 非 Error 状态确保不会遗留错误滤镜/动画
        if (state !is FloatingBallState.Error) {
            try {
                ballIcon?.clearColorFilter()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to clear color filter", e)
            }
        }

        when (state) {
            is FloatingBallState.Recording -> {
                try {
                    ballIcon?.setImageResource(R.drawable.microphone_floatingball)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to set ball icon (recording)", e)
                }
                processingSpinner?.visibility = View.GONE
                stopProcessingSpinner()
                startRippleAnimation()
                startRecordingBreathAnimation()
            }
            is FloatingBallState.Processing -> {
                try {
                    ballIcon?.setImageResource(R.drawable.microphone_floatingball)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to set ball icon (processing)", e)
                }
                stopRippleAnimation()
                stopRecordingBreathAnimation()
                processingSpinner?.visibility = View.VISIBLE
                startProcessingSpinner()
            }
            is FloatingBallState.Error -> {
                stopRippleAnimation()
                stopRecordingBreathAnimation()
                processingSpinner?.visibility = View.GONE
                stopProcessingSpinner()
                if (enteringError) playErrorShakeAnimation()
            }
            else -> {
                // Idle, MoveMode
                try {
                    ballIcon?.setImageResource(R.drawable.microphone_floatingball)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to set ball icon (idle/move)", e)
                }
                stopRippleAnimation()
                stopRecordingBreathAnimation()
                processingSpinner?.visibility = View.GONE
                stopProcessingSpinner()
                resetIconScale()
            }
        }
    }

    /**
     * 若当前在左右边缘，执行“完全显示”的贴边浮现动画。
     * - 仅对左右边缘生效；底部贴边不处理（保持完全显示）。
     */
    fun animateRevealFromEdgeIfNeeded() {
        val p = lp ?: return
        val v = ballView ?: return

        val dock = detectDockSide()
        if (dock == DockSide.BOTTOM || dock == DockSide.NONE) {
            try {
                switchToBallVisual(dock, animate = false)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to switch to ball visual (no-reveal dock)", e)
            }
            return
        }

        try {
            switchToBallVisual(dock, animate = true)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to switch to ball visual on reveal", e)
        }

        val target = fullyVisiblePositionForSide(dock)
        val startX = p.x
        val startY = p.y
        val dx = target.first - startX
        val dy = target.second - startY

        if (dx == 0 && dy == 0) return

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                p.x = (startX + dx * f).toInt()
                p.y = (startY + dy * f).toInt()
                try {
                    windowManager.updateViewLayout(ballView ?: v, p)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to update layout during reveal", e)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                }
            })
            start()
        }
    }

    /**
     * 若当前处于静息，应在左右贴边时执行半隐动画；
     * - 不对底部贴边执行半隐，保持完全可见。
     * - 若未贴边，则将自动吸附到就近左右边并半隐。
     */
    fun animateHideToEdgePartialIfNeeded() {
        val p = lp ?: return
        val v = ballView ?: return

        val dock = detectDockSide(allowChooseNearest = true)
        if (dock == DockSide.BOTTOM || dock == DockSide.NONE) {
            try {
                switchToBallVisual(dock, animate = false)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to switch to ball visual (no-hide dock)", e)
            }
            return
        }

        try {
            switchToEdgeHandleVisual(dock, animate = true)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to switch to edge-handle visual on hide", e)
        }

        val target = partiallyHiddenPositionForSide(dock)
        val startX = p.x
        val startY = p.y
        val dx = target.first - startX
        val dy = target.second - startY

        if (dx == 0 && dy == 0) return

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                p.x = (startX + dx * f).toInt()
                p.y = (startY + dy * f).toInt()
                try {
                    windowManager.updateViewLayout(ballView ?: v, p)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to update layout during partial hide", e)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                }
            })
            start()
        }
    }

    /** 显示完成对勾 */
    fun showCompletionTick(durationMs: Long = 1000L) {
        val icon = ballIcon ?: return
        stopRecordingBreathAnimation()
        try {
            icon.setImageResource(R.drawable.check_circle)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to set check icon", e)
            return
        }
        if (!completionResetPosted) {
            completionResetPosted = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    ballIcon?.setImageResource(R.drawable.microphone_floatingball)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to reset icon", e)
                }
                if (currentState is FloatingBallState.Recording) {
                    startRecordingBreathAnimation()
                }
                completionResetPosted = false
            }, durationMs)
        }
    }

    /** 对勾展示是否仍在活动周期内（用于延后半隐） */
    fun isCompletionTickActive(): Boolean = completionResetPosted

    /** 吸附到边缘（带动画） */
    fun animateSnapToEdge(v: View, onComplete: (() -> Unit)? = null) {
        val p = lp ?: return
        val (targetX, targetY) = calculateSnapTarget(v)

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
                try {
                    windowManager.updateViewLayout(ballView ?: v, p)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to update layout during snap animation", e)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                    onComplete?.invoke()
                }
            })
            start()
        }
    }

    /** 吸附到边缘（无动画） */
    fun snapToEdge(v: View) {
        val p = lp ?: return
        val (targetX, targetY) = calculateSnapTarget(v)
        p.x = targetX
        p.y = targetY
        try {
            windowManager.updateViewLayout(ballView ?: v, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update layout during snap", e)
        }
        persistBallPosition()
    }

    /** 获取悬浮球中心点 */
    fun getBallCenterSnapshot(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val sizeDp = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ball size, using default", e)
            56
        }
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (lp?.width ?: dp(sizeDp))
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (lp?.height ?: dp(sizeDp))
        val px = lp?.x ?: run {
            try {
                prefs.floatingBallPosX
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get ball X position, using default", e)
                (dm.widthPixels - vw) / 2
            }
        }
        val py = lp?.y ?: run {
            try {
                prefs.floatingBallPosY
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get ball Y position, using default", e)
                (dm.heightPixels - vh) / 2
            }
        }
        return (px + vw / 2) to (py + vh / 2)
    }

    /** 更新窗口位置 */
    fun updateViewLayout(v: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(v, params)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update view layout", e)
        }
    }

    /** 清理所有动画 */
    fun cleanup() {
        stopRippleAnimation()
        stopProcessingSpinner()
        stopRecordingBreathAnimation()
        edgeAnimator?.cancel()
        edgeAnimator = null
        cancelErrorVisual()
    }

    // ==================== 私有辅助方法 ====================

    private fun getMonetColor(attr: Int, fallback: Int): Int {
        val ctx = monetContext ?: context
        return try {
            val tv = android.util.TypedValue()
            val theme = ctx.theme
            if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve theme attribute $attr, using fallback", e)
            fallback
        }
    }

    private fun setupRippleBackgrounds(color: Int) {
        val rippleStrokeColor = applyAlpha(color, 1.0f)
        val strokeWidthPx = dp(3)
        val dashWidthPx = dp(8).toFloat()
        val dashGapPx = dp(6).toFloat()
        listOf(ripple1, ripple2, ripple3).forEach { ripple ->
            ripple?.let {
                try {
                    val drawable = android.graphics.drawable.GradientDrawable()
                    drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                    drawable.setColor(android.graphics.Color.TRANSPARENT)
                    drawable.setStroke(strokeWidthPx, rippleStrokeColor, dashWidthPx, dashGapPx)
                    it.background = drawable
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to set ripple background", e)
                }
            }
        }
        applyRippleClipOutline()
        applyRippleOriginOffset()
    }

    private fun switchToEdgeHandleVisual(dock: DockSide, animate: Boolean) {
        if (dock == DockSide.BOTTOM || dock == DockSide.NONE) return
        val handle = edgeHandleIcon ?: return
        val icon = ballIcon ?: return

        applyEdgeHandleDockVisual(dock)
        if (edgeHandleVisible && handle.visibility == View.VISIBLE && icon.visibility != View.VISIBLE) return

        edgeHandleVisible = true

        val offset = dp(8).toFloat()
        val handleStartX = if (dock == DockSide.LEFT) offset else -offset
        val iconEndX = if (dock == DockSide.LEFT) -offset else offset

        cancelViewAnimator(handle)
        cancelViewAnimator(icon)

        handle.visibility = View.VISIBLE
        icon.visibility = View.VISIBLE

        if (!animate) {
            handle.alpha = 1f
            handle.translationX = 0f
            icon.alpha = 0f
            icon.translationX = 0f
            icon.visibility = View.INVISIBLE
            return
        }

        handle.alpha = 0f
        handle.translationX = handleStartX
        handle.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(230)
            .setInterpolator(DecelerateInterpolator())
            .start()

        icon.alpha = 1f
        icon.translationX = 0f
        icon.animate()
            .alpha(0f)
            .translationX(iconEndX)
            .setDuration(230)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                icon.visibility = View.INVISIBLE
                icon.translationX = 0f
            }
            .start()
    }

    private fun switchToBallVisual(dock: DockSide, animate: Boolean) {
        val handle = edgeHandleIcon ?: return
        val icon = ballIcon ?: return

        if (!edgeHandleVisible && handle.visibility != View.VISIBLE && icon.visibility == View.VISIBLE) return

        edgeHandleVisible = false

        val offset = dp(8).toFloat()
        val iconStartX = if (dock == DockSide.LEFT) -offset else offset
        val handleEndX = if (dock == DockSide.LEFT) offset else -offset

        cancelViewAnimator(handle)
        cancelViewAnimator(icon)

        icon.visibility = View.VISIBLE

        if (!animate) {
            icon.alpha = 1f
            icon.translationX = 0f
            handle.alpha = 0f
            handle.translationX = 0f
            handle.visibility = View.GONE
            return
        }

        icon.alpha = 0f
        icon.translationX = iconStartX
        icon.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(230)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (handle.visibility != View.VISIBLE) {
            handle.visibility = View.VISIBLE
            handle.alpha = 0f
            handle.translationX = 0f
        }
        handle.animate()
            .alpha(0f)
            .translationX(handleEndX)
            .setDuration(230)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                handle.visibility = View.GONE
                handle.translationX = 0f
            }
            .start()
    }

    private fun applyEdgeHandleDockVisual(dock: DockSide) {
        val handle = edgeHandleIcon ?: return
        val lp = handle.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        when (dock) {
            DockSide.LEFT -> {
                handle.setImageResource(R.drawable.angle_bracket_right)
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            DockSide.RIGHT -> {
                handle.setImageResource(R.drawable.angle_bracket_left)
                lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }
            else -> return
        }
        handle.layoutParams = lp
    }

    private fun cancelViewAnimator(v: View) {
        try {
            v.animate().cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel view animator", e)
        }
    }

    private fun applyRippleClipOutline() {
        val clip = rippleClip ?: return
        clip.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width
                val h = view.height
                val side = minOf(w, h)
                if (side <= 0) {
                    outline.setEmpty()
                    return
                }
                val inset = (side * RIPPLE_CLIP_INSET_FRACTION).toInt()
                val left = (w - side) / 2 + inset
                val top = (h - side) / 2 + inset
                val right = (w + side) / 2 - inset
                val bottom = (h + side) / 2 - inset
                if (right <= left || bottom <= top) {
                    outline.setEmpty()
                    return
                }
                outline.setOval(left, top, right, bottom)
            }
        }
        clip.clipToOutline = true
        clip.invalidateOutline()
    }

    private fun applyRippleOriginOffset() {
        listOf(ripple1, ripple2, ripple3).forEach { ripple ->
            ripple ?: return@forEach
            val w = ripple.width.toFloat()
            val h = ripple.height.toFloat()
            if (w <= 0f || h <= 0f) return@forEach

            val centerX = w / 2f
            val centerY = h / 2f
            ripple.pivotX = centerX
            ripple.pivotY = centerY
            ripple.translationX = w * RIPPLE_ORIGIN_X_FRACTION - centerX
            ripple.translationY = h * RIPPLE_ORIGIN_Y_FRACTION - centerY
        }
    }

    private fun setupProcessingSpinner(ballContainer: android.widget.FrameLayout?, color: Int) {
        try {
            if (ballContainer == null) return

            // 若为首次或已释放，则创建新实例
            if (processingSpinner == null) {
                processingSpinner = ProcessingSpinnerView(context)
            }

            // 统一进行属性配置
            processingSpinner?.apply {
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setSpinnerColor(applyAlpha(color, 0.6f))
                setStrokeWidth(dp(4).toFloat())
                setSweepAngle(110f)
                // 初始化时保持隐藏，待 Processing 态再显现
                visibility = View.GONE
            }

            // 若当前未挂载到新的容器，则挂载
            val parent = processingSpinner?.parent
            if (parent == null) {
                val lpSpinner = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply { gravity = Gravity.CENTER }
                ballContainer.addView(processingSpinner, lpSpinner)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to setup processing spinner", e)
        }
    }

    private fun createWindowLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val size = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ball size, using default", e)
            56
        }
        val params = WindowManager.LayoutParams(
            dp(size),
            dp(size),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START

        val defaultX = dp(12)
        val defaultY = dp(180)
        val (screenW, screenH) = getUsableScreenSize()
        val vw = params.width
        val vh = params.height

        val sx = try {
            prefs.floatingBallPosX
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read legacy X", t)
            -1
        }
        val sy = try {
            prefs.floatingBallPosY
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read legacy Y", t)
            -1
        }
        val legacyX = if (sx == -1) defaultX else sx
        val legacyY = if (sy == -1) defaultY else sy

        // 优先使用“贴边锚点”恢复位置，避免横竖屏切换后绝对坐标导致偏移到屏幕中间
        val anchor = try {
            readDockAnchorFromPrefs() ?: run {
                computeDockAnchorFromLegacyPosition(legacyX, legacyY, screenW, screenH, vw, vh)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to restore ball anchor, using default", e)
            computeDockAnchorFromLegacyPosition(defaultX, defaultY, screenW, screenH, vw, vh)
        }
        val (rx, ry) = positionForDockAnchor(anchor, screenW, screenH, vw, vh, visibleXHint = legacyX)
        params.x = rx
        params.y = ry
        cachedDockAnchor = anchor

        return params
    }

    /** 重置位置为默认（左上偏下），并立即应用到当前视图（若存在） */
    fun resetPositionToDefault() {
        val p = lp ?: return
        val v = ballView ?: return
        try {
            p.x = dp(12)
            p.y = dp(180)
            windowManager.updateViewLayout(v, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to reset position to default", e)
        }
        // 覆盖并持久化当前位置（含贴边锚点）
        persistBallPosition()
    }

    /**
     * 当横竖屏切换/窗口尺寸变化时，根据“贴边锚点”重新计算位置。
     * 目标：保持左右/底部贴边与相对位置一致，避免旋转后跑到屏幕中间。
     */
    fun remapPositionForCurrentDisplay(reason: String = "config_changed") {
        val v = ballView ?: return
        val p = lp ?: return

        val anchor = cachedDockAnchor ?: readDockAnchorFromPrefs() ?: run {
            Log.w(TAG, "No cached/persisted dock anchor; fallback to current layout ($reason)")
            computeDockAnchorForCurrentLayout()
        } ?: return

        try {
            val (screenW, screenH) = getUsableScreenSize()
            val vw = (v.width.takeIf { it > 0 }) ?: p.width
            val vh = (v.height.takeIf { it > 0 }) ?: p.height
            val (nx, ny) = positionForDockAnchor(anchor, screenW, screenH, vw, vh, visibleXHint = p.x)
            p.x = nx
            p.y = ny
            windowManager.updateViewLayout(v, p)
            cachedDockAnchor = anchor
            persistBallPosition()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to remap ball position on display change: $reason", e)
        }
    }

    private fun calculateSnapTarget(v: View): Pair<Int, Int> {
        val p = lp ?: return 0 to 0
        val (screenW, screenH) = getUsableScreenSize()
        val def = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ball size for snap calculation", e)
            56
        }
        val root = ballView ?: v
        val vw = if (root.width > 0) root.width else dp(def)
        val vh = if (root.height > 0) root.height else dp(def)
        val margin = dp(0)

        val bottomSnapThreshold = dp(64)
        val bottomY = (screenH - vh - margin).coerceAtLeast(0)
        val bottomDist = bottomY - p.y

        return if (bottomDist <= bottomSnapThreshold) {
            val targetY = bottomY
            val minX = margin
            val maxX = (screenW - vw - margin).coerceAtLeast(minX)
            val targetX = p.x.coerceIn(minX, maxX)
            // 再次保护，避免任何计算差异导致越界
            val safeX = targetX.coerceIn(minX, maxX)
            val safeY = targetY.coerceIn(0, (screenH - vh - margin).coerceAtLeast(0))
            safeX to safeY
        } else {
            val centerX = p.x + vw / 2
            val dockLeft = centerX < screenW / 2
            val fullX = if (dockLeft) margin else (screenW - vw - margin)
            val hidden = if (dockLeft) p.x < margin else p.x > fullX
            val targetX = if (edgeHandleVisible && hidden) {
                val visibleW = visibleWidthWhenHidden(vw)
                if (dockLeft) (margin - (vw - visibleW)) else (screenW - visibleW - margin)
            } else {
                fullX
            }
            val minY = margin
            val maxY = (screenH - vh - margin).coerceAtLeast(minY)
            val targetY = p.y.coerceIn(minY, maxY)
            targetX to targetY
        }
    }

    // ============== 贴边/半隐计算 ==============

    private enum class DockSide { LEFT, RIGHT, BOTTOM, NONE }

    private data class DockAnchor(
        val side: DockSide,
        val fraction: Float,
        val hidden: Boolean,
    )

    private fun dockSideToPrefValue(side: DockSide): Int {
        return when (side) {
            DockSide.LEFT -> 1
            DockSide.RIGHT -> 2
            DockSide.BOTTOM -> 3
            DockSide.NONE -> 0
        }
    }

    private fun dockSideFromPrefValue(value: Int): DockSide {
        return when (value) {
            1 -> DockSide.LEFT
            2 -> DockSide.RIGHT
            3 -> DockSide.BOTTOM
            else -> DockSide.NONE
        }
    }

    private fun readDockAnchorFromPrefs(): DockAnchor? {
        return try {
            val side = dockSideFromPrefValue(prefs.floatingBallDockSide)
            val fraction = prefs.floatingBallDockFraction
            if (side == DockSide.NONE || fraction < 0f) return null
            val hidden = prefs.floatingBallDockHidden
            DockAnchor(side, fraction.coerceIn(0f, 1f), hidden)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read floating ball dock anchor", e)
            null
        }
    }

    private fun computeDockAnchorFromLegacyPosition(
        x: Int,
        y: Int,
        screenW: Int,
        screenH: Int,
        vw: Int,
        vh: Int,
    ): DockAnchor {
        val margin = dp(0)
        val minX = margin
        val maxX = (screenW - vw - margin).coerceAtLeast(minX)
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)

        val bottomY = maxY
        val bottomSnapThreshold = dp(64)
        val side = if (bottomY - y <= bottomSnapThreshold || y >= bottomY) {
            DockSide.BOTTOM
        } else {
            val leftX = minX
            val rightX = maxX
            val edgeThresholdX = dp(28)
            when {
                x <= leftX + edgeThresholdX -> DockSide.LEFT
                x >= rightX - edgeThresholdX -> DockSide.RIGHT
                else -> {
                    val centerX = x + vw / 2
                    if (centerX < screenW / 2) DockSide.LEFT else DockSide.RIGHT
                }
            }
        }

        val hidden = when (side) {
            DockSide.LEFT -> x < minX
            DockSide.RIGHT -> x > maxX
            else -> false
        }

        val fraction = when (side) {
            DockSide.BOTTOM -> {
                val denom = (maxX - minX).toFloat()
                if (denom <= 0f) 0f else (x.coerceIn(minX, maxX) - minX).toFloat() / denom
            }
            DockSide.LEFT, DockSide.RIGHT -> {
                val denom = (maxY - minY).toFloat()
                if (denom <= 0f) 0f else (y.coerceIn(minY, maxY) - minY).toFloat() / denom
            }
            DockSide.NONE -> 0f
        }

        return DockAnchor(side, fraction.coerceIn(0f, 1f), hidden)
    }

    private fun computeDockAnchorForCurrentLayout(): DockAnchor? {
        val p = lp ?: return null
        val (screenW, screenH) = getUsableScreenSize()
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (p.width)
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (p.height)
        val side = detectDockSide(allowChooseNearest = true)

        val margin = dp(0)
        val minX = margin
        val maxX = (screenW - vw - margin).coerceAtLeast(minX)
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)

        val hidden = when (side) {
            DockSide.LEFT -> p.x < minX
            DockSide.RIGHT -> p.x > maxX
            else -> false
        }

        val fraction = when (side) {
            DockSide.BOTTOM -> {
                val denom = (maxX - minX).toFloat()
                if (denom <= 0f) 0f else (p.x.coerceIn(minX, maxX) - minX).toFloat() / denom
            }
            DockSide.LEFT, DockSide.RIGHT -> {
                val denom = (maxY - minY).toFloat()
                if (denom <= 0f) 0f else (p.y.coerceIn(minY, maxY) - minY).toFloat() / denom
            }
            DockSide.NONE -> 0f
        }

        return DockAnchor(side, fraction.coerceIn(0f, 1f), hidden)
    }

    private fun positionForDockAnchor(
        anchor: DockAnchor,
        screenW: Int,
        screenH: Int,
        vw: Int,
        vh: Int,
        visibleXHint: Int? = null,
    ): Pair<Int, Int> {
        val margin = dp(0)
        val minX = margin
        val maxX = (screenW - vw - margin).coerceAtLeast(minX)
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val edgeThresholdX = dp(28)

        val f = anchor.fraction.coerceIn(0f, 1f)
        val y = (minY + (maxY - minY) * f).toInt().coerceIn(minY, maxY)

        return when (anchor.side) {
            DockSide.LEFT -> {
                val visibleW = visibleWidthWhenHidden(vw)
                val x = if (anchor.hidden) {
                    (minX - (vw - visibleW))
                } else {
                    val candidateX = visibleXHint?.coerceIn(minX, maxX)
                    if (candidateX != null && candidateX <= minX + edgeThresholdX) candidateX else minX
                }
                x to y
            }
            DockSide.RIGHT -> {
                val visibleW = visibleWidthWhenHidden(vw)
                val fullX = maxX
                val x = if (anchor.hidden) {
                    (screenW - visibleW - margin)
                } else {
                    val candidateX = visibleXHint?.coerceIn(minX, maxX)
                    if (candidateX != null && candidateX >= fullX - edgeThresholdX) candidateX else fullX
                }
                x to y
            }
            DockSide.BOTTOM -> {
                val x = (minX + (maxX - minX) * f).toInt().coerceIn(minX, maxX)
                x to maxY
            }
            DockSide.NONE -> minX to y
        }
    }

    private fun visibleWidthWhenHidden(vw: Int): Int {
        val handle = edgeHandleIcon
        val lpW = handle?.layoutParams?.width
        val w = when {
            handle != null && handle.width > 0 -> handle.width
            lpW != null && lpW > 0 -> lpW
            else -> dp(EDGE_HANDLE_SIZE_DP)
        }
        return w.coerceIn(1, vw)
    }

    /**
     * 检测当前贴边方向；当 allowChooseNearest=true 且未贴边时，选择更近的左右边。
     */
    private fun detectDockSide(allowChooseNearest: Boolean = false): DockSide {
        val p = lp ?: return DockSide.NONE
        val (screenW, screenH) = getUsableScreenSize()
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (p.width)
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (p.height)
        val margin = dp(0)

        val bottomY = (screenH - vh - margin).coerceAtLeast(0)
        val bottomDist = bottomY - p.y
        val bottomSnapThreshold = dp(64)
        if (bottomDist <= bottomSnapThreshold || p.y >= bottomY) return DockSide.BOTTOM

        val leftX = margin
        val rightX = screenW - vw - margin
        val edgeThresholdX = dp(28)

        if (p.x <= leftX + edgeThresholdX) return DockSide.LEFT
        if (p.x >= rightX - edgeThresholdX) return DockSide.RIGHT

        if (!allowChooseNearest) return DockSide.NONE

        // 未贴边时，选择更近的一侧（不考虑顶部/底部，因为仅对左右生效）
        val centerX = p.x + vw / 2
        return if (centerX < screenW / 2) DockSide.LEFT else DockSide.RIGHT
    }

    /** 左/右侧完全可见位置 */
    private fun fullyVisiblePositionForSide(side: DockSide): Pair<Int, Int> {
        val p = lp ?: return 0 to 0
        val (screenW, screenH) = getUsableScreenSize()
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (p.width)
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (p.height)
        val margin = dp(0)
        val x = when (side) {
            DockSide.LEFT -> margin
            DockSide.RIGHT -> screenW - vw - margin
            else -> p.x
        }
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val y = p.y.coerceIn(minY, maxY)
        return x to y
    }

    /** 左/右侧半隐位置（仅显示“箭头把手”的宽度） */
    private fun partiallyHiddenPositionForSide(side: DockSide): Pair<Int, Int> {
        val p = lp ?: return 0 to 0
        val (screenW, screenH) = getUsableScreenSize()
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (p.width)
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (p.height)
        val margin = dp(0)

        val visibleW = visibleWidthWhenHidden(vw)
        val x = when (side) {
            DockSide.LEFT -> (margin - (vw - visibleW))
            DockSide.RIGHT -> (screenW - visibleW - margin)
            else -> p.x
        }
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val y = p.y.coerceIn(minY, maxY)
        return x to y
    }

    /**
     * 获取可用的屏幕宽高（排除系统状态栏/导航栏/切口），用于限制悬浮球不被放置到不可见区域。
     * 说明：横向半隐依赖 FLAG_LAYOUT_NO_LIMITS，仅在 X 轴允许越界；Y 轴一律限制在可见范围内。
     */
    private fun getUsableScreenSize(): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                val bounds = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                val w = (bounds.width() - insets.left - insets.right).coerceAtLeast(0)
                val h = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(0)
                w to h
            } else {
                val dm = context.resources.displayMetrics
                dm.widthPixels to dm.heightPixels
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get usable screen size, fallback to displayMetrics", e)
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun persistBallPosition() {
        val p = lp ?: return
        try {
            prefs.floatingBallPosX = p.x
            prefs.floatingBallPosY = p.y
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist ball position", e)
        }

        val anchor = try {
            computeDockAnchorForCurrentLayout()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to compute dock anchor for ball", e)
            null
        } ?: return

        cachedDockAnchor = anchor
        try {
            prefs.floatingBallDockSide = dockSideToPrefValue(anchor.side)
            prefs.floatingBallDockFraction = anchor.fraction
            prefs.floatingBallDockHidden = anchor.hidden
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist ball dock anchor", e)
        }
    }

    // ==================== 动画方法 ====================

    private fun startRippleAnimation() {
        stopRippleAnimation()

        val dx = RIPPLE_ORIGIN_X_FRACTION - 0.5f
        val dy = RIPPLE_ORIGIN_Y_FRACTION - 0.5f
        val offset = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val clipRadiusFraction = (1f - 2f * RIPPLE_CLIP_INSET_FRACTION).coerceIn(0.1f, 1f)
        val maxScale = (clipRadiusFraction + 2f * offset + RIPPLE_MAX_SCALE_EXTRA).coerceAtLeast(clipRadiusFraction)

        val ripples = listOf(ripple1, ripple2, ripple3)
        ripples.forEachIndexed { index, ripple ->
            ripple ?: return@forEachIndexed

            val delay = index * 500L

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                startDelay = delay
                interpolator = android.view.animation.LinearInterpolator()

                addUpdateListener { anim ->
                    val progress = anim.animatedValue as Float
                    val scale = RIPPLE_START_SCALE + progress * (maxScale - RIPPLE_START_SCALE)
                    ripple.scaleX = scale
                    ripple.scaleY = scale
                    val alpha = sqrt((1f - progress).coerceIn(0f, 1f).toDouble()).toFloat()
                    ripple.alpha = alpha
                    ripple.visibility = if (alpha > 0.01f) View.VISIBLE else View.INVISIBLE
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationRepeat(animation: Animator) {
                        ripple.scaleX = RIPPLE_START_SCALE
                        ripple.scaleY = RIPPLE_START_SCALE
                    }
                })

                start()
            }

            rippleAnimators.add(animator)
        }
    }

    private fun stopRippleAnimation() {
        rippleAnimators.forEach { it.cancel() }
        rippleAnimators.clear()
        ripple1?.visibility = View.INVISIBLE
        ripple2?.visibility = View.INVISIBLE
        ripple3?.visibility = View.INVISIBLE
        ripple1?.alpha = 0f
        ripple2?.alpha = 0f
        ripple3?.alpha = 0f
    }

    private fun startProcessingSpinner() {
        try {
            processingSpinner?.start()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start processing spinner", e)
        }
    }

    private fun stopProcessingSpinner() {
        try {
            processingSpinner?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to stop processing spinner", e)
        }
    }

    private fun startRecordingBreathAnimation() {
        val icon = ballIcon ?: return

        stopRecordingBreathAnimation(resetVisual = false)

        val minAlpha = 125
        val maxAlpha = 255
        val alphaRange = (maxAlpha - minAlpha).toFloat()

        recordingBreathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = (anim.animatedValue as? Float) ?: return@addUpdateListener
                icon.imageAlpha = (minAlpha + alphaRange * t).toInt().coerceIn(0, 255)
            }
            start()
        }
    }

    private fun stopRecordingBreathAnimation(resetVisual: Boolean = true) {
        try {
            recordingBreathAnimator?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel recording breath animator", e)
        }
        recordingBreathAnimator = null

        if (!resetVisual) return
        val icon = ballIcon ?: return
        icon.scaleX = 1.0f
        icon.scaleY = 1.0f
        icon.imageAlpha = 255
    }

    private fun resetIconScale() {
        ballIcon?.let {
            it.scaleX = 1.0f
            it.scaleY = 1.0f
        }
    }

    private fun playErrorShakeAnimation() {
        val icon = ballIcon ?: return

        cancelErrorVisual()

        errorVisualActive = true
        try {
            icon.animate().cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel icon animation", e)
        }
        icon.setColorFilter(UiColors.error(icon))

        var canceled = false
        val shake = ValueAnimator.ofFloat(0f, -16f, 16f, -12f, 12f, -6f, 6f, 0f).apply {
            duration = 500
            addUpdateListener { anim ->
                icon.translationX = (anim.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (errorShakeAnimator === animation) {
                        errorShakeAnimator = null
                    }
                    icon.translationX = 0f
                    if (canceled) return

                    val clearRunnable = Runnable {
                        try {
                            icon.clearColorFilter()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to clear color filter", e)
                        }
                        errorVisualActive = false
                    }
                    errorClearRunnable = clearRunnable
                    try {
                        mainHandler.postDelayed(clearRunnable, 1000)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to post delayed clear runnable", e)
                        errorVisualActive = false
                    }
                }
            })
        }
        errorShakeAnimator = shake
        shake.start()
    }

    private fun cancelErrorVisual() {
        errorClearRunnable?.let { runnable ->
            try {
                mainHandler.removeCallbacks(runnable)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to remove error clear runnable", e)
            }
        }
        errorClearRunnable = null

        try {
            errorShakeAnimator?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel error shake animator", e)
        }
        errorShakeAnimator = null

        ballIcon?.let { icon ->
            icon.translationX = 0f
            try {
                icon.clearColorFilter()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to clear color filter", e)
            }
        }

        errorVisualActive = false
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dp(v: Int): Int {
        val d = context.resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
