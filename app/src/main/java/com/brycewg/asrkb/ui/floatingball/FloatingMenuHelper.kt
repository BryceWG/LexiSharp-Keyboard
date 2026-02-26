package com.brycewg.asrkb.ui.floatingball

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.UiColors
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.HapticFeedbackHelper
import com.google.android.material.color.DynamicColors

/**
 * 悬浮菜单辅助类
 * 提供通用的菜单创建、显示、隐藏逻辑
 * FloatingAsrService 和 FloatingImeSwitcherService 共享此工具
 *
 * 自动应用动态主题上下文（Material3 + Monet），确保与系统主题一致
 */
class FloatingMenuHelper(
    rawContext: Context,
    private val windowManager: WindowManager,
) {
    companion object {
        private const val TAG = "FloatingMenuHelper"
    }

    // 包裹动态主题的上下文，用于创建所有视图
    private val context: Context = run {
        val themedCtx = ContextThemeWrapper(rawContext, R.style.Theme_ASRKeyboard)
        val dynCtx = DynamicColors.wrapContextIfAvailable(themedCtx)
        dynCtx
    }
    private val prefs = Prefs(rawContext)

    /**
     * 拖拽选择会话控制器
     * - 负责基于屏幕坐标进行命中测试与高亮
     * - 提供在抬手时执行对应项点击并关闭菜单的能力
     */
    inner class DragRadialMenuSession internal constructor(
        private val context: Context,
        private val windowManager: WindowManager,
        val root: View,
        private val container: LinearLayout,
        private val itemRows: List<View>,
        private val actions: List<() -> Unit>,
        private val onDismiss: () -> Unit,
    ) {
        private var highlightedIndex: Int = -1
        private var dismissed: Boolean = false

        /** 更新悬停高亮，返回命中索引（未命中为 -1） */
        fun updateHover(rawX: Float, rawY: Float): Int {
            val x = rawX.toInt()
            val y = rawY.toInt()
            var hit = -1
            itemRows.forEachIndexed { index, row ->
                val loc = IntArray(2)
                try {
                    row.getLocationOnScreen(loc)
                } catch (_: Throwable) {
                    return@forEachIndexed
                }
                val left = loc[0]
                val top = loc[1]
                val right = left + row.width
                val bottom = top + row.height
                if (x in left..right && y in top..bottom) {
                    hit = index
                }
            }
            if (hit != highlightedIndex) {
                // 切换按压态作为高亮效果
                itemRows.forEachIndexed { i, v ->
                    try {
                        v.isPressed = (i == hit)
                    } catch (_: Throwable) { /* ignore visual error */ }
                }
                highlightedIndex = hit
            }
            return hit
        }

        /** 在当前位置执行选择；若未命中则仅关闭菜单 */
        fun performSelectionAt(rawX: Float, rawY: Float) {
            val hit = updateHover(rawX, rawY)
            if (hit >= 0) {
                // 触发反馈与点击
                HapticFeedbackHelper.performTap(context, prefs, root)
                try {
                    actions.getOrNull(hit)?.invoke()
                } catch (e: Throwable) {
                    Log.e(TAG, "Drag select action failed", e)
                }
            }
            dismiss()
        }

        fun dismiss() {
            if (dismissed) return
            dismissed = true
            try {
                this@FloatingMenuHelper.cancelAllAnimations(root)
                windowManager.removeView(root)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to remove drag radial root", e)
            }
            try {
                onDismiss.invoke()
            } catch (e: Throwable) {
                Log.w(TAG, "onDismiss error in drag session", e)
            }
        }
    }

    /**
     * 菜单项数据类
     */
    data class MenuItem(
        val iconRes: Int,
        val label: String,
        val contentDescription: String,
        val onClick: () -> Unit,
    )

    /**
     * 创建并显示轮盘菜单
     * @param anchorCenter 悬浮球中心点坐标
     * @param alpha UI 透明度
     * @param items 菜单项列表
     * @param onDismiss 菜单关闭回调
     * @return 菜单根视图
     */
    fun showRadialMenu(
        anchorCenter: Pair<Int, Int>,
        alpha: Float,
        items: List<MenuItem>,
        onDismiss: () -> Unit,
    ): View? {
        try {
            val root = android.widget.FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    try {
                        windowManager.removeView(this)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to remove radial root on blank click", e)
                    }
                    onDismiss()
                }
            }
            root.alpha = 1.0f
            try {
                root.requestFocus()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to request focus for radial root", e)
            }

            val (centerX, centerY) = anchorCenter
            val isLeft = centerX < (context.resources.displayMetrics.widthPixels / 2)

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_panel_round)
                val pad = dp(8)
                setPadding(pad, pad, pad, pad)
            }

            items.forEachIndexed { index, item ->
                val row = buildCapsule(item.iconRes, item.label, item.contentDescription) {
                    // 先执行动作，再移除菜单：确保动作期间应用保持前台焦点（例如读取剪贴板）
                    try {
                        item.onClick()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Radial item action failed", e)
                    }
                    try {
                        windowManager.removeView(root)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to remove radial root on item click", e)
                    }
                    onDismiss()
                }
                val lpRow = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (index > 0) lpRow.topMargin = dp(6)
                container.addView(row, lpRow)
            }

            container.alpha = 0f
            container.translationX = if (isLeft) dp(8).toFloat() else -dp(8).toFloat()
            val paramsContainer = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            root.addView(container, paramsContainer)

            container.post {
                try {
                    positionContainer(container, centerX, centerY, isLeft)
                    container.animate().alpha(1f).translationX(0f).setDuration(160).start()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to position container", e)
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 允许获得焦点，以便本应用在菜单展示期间具备前台焦点（例如读取剪贴板）
                0,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.START

            windowManager.addView(root, params)
            return root
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show radial menu", e)
            return null
        }
    }

    /**
     * 创建并显示支持“拖拽选中”的轮盘菜单，返回控制会话
     */
    fun showRadialMenuForDrag(
        anchorCenter: Pair<Int, Int>,
        alpha: Float,
        items: List<MenuItem>,
        onDismiss: () -> Unit,
    ): DragRadialMenuSession? {
        try {
            val root = android.widget.FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    try {
                        windowManager.removeView(this)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to remove drag radial root on blank click", e)
                    }
                    onDismiss()
                }
            }
            root.alpha = 1.0f
            try {
                root.requestFocus()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to request focus for drag radial root", e)
            }

            val (centerX, centerY) = anchorCenter
            val isLeft = centerX < (context.resources.displayMetrics.widthPixels / 2)

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_panel_round)
                val pad = dp(8)
                setPadding(pad, pad, pad, pad)
            }

            val rows = ArrayList<View>(items.size)
            val actions = ArrayList<() -> Unit>(items.size)

            items.forEachIndexed { index, item ->
                val row = buildCapsule(item.iconRes, item.label, item.contentDescription) {
                    // 点击备用：也允许直接轻触选择
                    try {
                        item.onClick()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Drag radial item action failed", e)
                    }
                    try {
                        windowManager.removeView(root)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to remove drag radial root on item click", e)
                    }
                    onDismiss()
                }
                rows.add(row)
                actions.add(item.onClick)

                val lpRow = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (index > 0) lpRow.topMargin = dp(6)
                container.addView(row, lpRow)
            }

            container.alpha = 0f
            container.translationX = if (isLeft) dp(8).toFloat() else -dp(8).toFloat()
            val paramsContainer = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            root.addView(container, paramsContainer)

            container.post {
                try {
                    positionContainer(container, centerX, centerY, isLeft)
                    container.animate().alpha(1f).translationX(0f).setDuration(160).start()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to position container (drag)", e)
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.START

            windowManager.addView(root, params)
            return DragRadialMenuSession(context, windowManager, root, container, rows, actions, onDismiss)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show radial menu (drag)", e)
            return null
        }
    }

    /**
     * 创建并显示列表面板（供应商选择、Prompt 预设等）
     * @param anchorCenter 悬浮球中心点坐标
     * @param alpha UI 透明度
     * @param title 面板标题
     * @param entries 条目列表（label, isSelected, onClick）
     * @param onDismiss 面板关闭回调
     * @return 面板根视图
     */
    fun showListPanel(
        anchorCenter: Pair<Int, Int>,
        alpha: Float,
        title: String,
        entries: List<Triple<String, Boolean, () -> Unit>>,
        onDismiss: () -> Unit,
    ): View? {
        try {
            val root = android.widget.FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    try {
                        windowManager.removeView(this)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to remove panel root on blank click", e)
                    }
                    onDismiss()
                }
            }
            root.alpha = 1.0f
            try {
                root.requestFocus()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to request focus for panel root", e)
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_panel_round)
                val pad = dp(12)
                setPadding(pad, pad, pad, pad)
            }

            val titleView = TextView(context).apply {
                text = title
                setTextColor(UiColors.panelFg(context))
                textSize = 16f
                setPadding(0, 0, 0, dp(4))
            }
            container.addView(
                titleView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            entries.forEach { (label, isSelected, onClick) ->
                val tv = TextView(context).apply {
                    text = if (isSelected) "✓  $label" else label
                    setTextColor(UiColors.panelFgVariant(context))
                    textSize = 14f
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        hapticFeedback(this)
                        // 同样先执行动作，再移除面板，保证动作期间具备前台焦点
                        try {
                            onClick()
                        } catch (e: Throwable) {
                            Log.e(TAG, "Panel item action failed", e)
                        }
                        try {
                            windowManager.removeView(root)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to remove panel root on item click", e)
                        }
                        onDismiss()
                    }
                }
                container.addView(
                    tv,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

            val paramsContainer = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            root.addView(container, paramsContainer)

            val (centerX, centerY) = anchorCenter
            val isLeft = centerX < (context.resources.displayMetrics.widthPixels / 2)
            container.alpha = 0f
            container.translationX = if (isLeft) dp(8).toFloat() else -dp(8).toFloat()
            container.post {
                try {
                    positionContainer(container, centerX, centerY, isLeft)
                    container.animate().alpha(1f).translationX(0f).setDuration(160).start()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to position panel", e)
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.START

            windowManager.addView(root, params)
            return root
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show list panel", e)
            return null
        }
    }

    /**
     * 创建并显示“文本列表”面板（支持滚动），用于展示识别历史纯文本
     * @param anchorCenter 悬浮球中心点坐标
     * @param alpha UI 透明度
     * @param title 标题
     * @param texts 文本条目列表，仅展示内容
     * @param onItemClick 点击条目回调（已在本方法中先执行，再移除面板）
     * @param initialVisibleCount 初次渲染的条目数量上限
     * @param loadMoreCount 每次滚动触发追加的条目数量
     * @param onDismiss 面板关闭回调
     */
    fun showScrollableTextPanel(
        anchorCenter: Pair<Int, Int>,
        alpha: Float,
        title: String,
        texts: List<String>,
        onItemClick: (String) -> Unit,
        initialVisibleCount: Int = Int.MAX_VALUE,
        loadMoreCount: Int = Int.MAX_VALUE,
        onDismiss: () -> Unit,
    ): View? {
        try {
            val root = android.widget.FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    try {
                        windowManager.removeView(this)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to remove text panel root on blank click", e)
                    }
                    onDismiss()
                }
            }
            root.alpha = 1.0f
            try {
                root.requestFocus()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to request focus for text panel root", e)
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_panel_round)
                val pad = dp(12)
                setPadding(pad, pad, pad, pad)
            }

            val titleView = TextView(context).apply {
                text = title
                setTextColor(UiColors.panelFg(context))
                textSize = 16f
                setPadding(0, 0, 0, dp(4))
            }
            container.addView(
                titleView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )

            val scroll = android.widget.ScrollView(context).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(
                list,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            container.addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4)
                },
            )

            val normalizedInitial = when {
                initialVisibleCount <= 0 -> 0
                initialVisibleCount == Int.MAX_VALUE -> texts.size
                else -> initialVisibleCount
            }
            val normalizedLoadMore = when {
                loadMoreCount <= 0 -> maxOf(1, normalizedInitial.coerceAtLeast(1))
                else -> loadMoreCount
            }
            var loadedCount = 0

            fun addTextView(text: String) {
                val tv = TextView(context).apply {
                    this.text = text
                    setTextColor(UiColors.panelFgVariant(context))
                    textSize = 14f
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    isClickable = true
                    isFocusable = true
                    background = ContextCompat.getDrawable(context, R.drawable.ripple_capsule)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener {
                        hapticFeedback(this)
                        try {
                            onItemClick(text)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Text item action failed", e)
                        }
                        try {
                            windowManager.removeView(root)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to remove text panel root on item click", e)
                        }
                        onDismiss()
                    }
                }
                list.addView(
                    tv,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(4) },
                )
            }

            fun appendBatch(maxCount: Int) {
                if (texts.isEmpty() || loadedCount >= texts.size) return
                val remaining = texts.size - loadedCount
                val target = if (maxCount == Int.MAX_VALUE) remaining else maxCount.coerceAtMost(remaining)
                if (target <= 0) return
                texts.subList(loadedCount, loadedCount + target).forEach { text ->
                    addTextView(text)
                }
                loadedCount += target
            }

            appendBatch(normalizedInitial)

            val threshold = dp(24)
            if (loadedCount < texts.size) {
                scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    val child = try {
                        scroll.getChildAt(0)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to get scroll child on change", e)
                        null
                    } ?: return@setOnScrollChangeListener
                    val remaining = child.measuredHeight - (scrollY + scroll.height)
                    if (remaining <= threshold) {
                        appendBatch(normalizedLoadMore)
                        if (loadedCount >= texts.size) {
                            scroll.setOnScrollChangeListener(null)
                        }
                    }
                }
            }

            val paramsContainer = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            root.addView(container, paramsContainer)

            val (centerX, centerY) = anchorCenter
            val isLeft = centerX < (context.resources.displayMetrics.widthPixels / 2)
            container.alpha = 0f
            container.translationX = if (isLeft) dp(8).toFloat() else -dp(8).toFloat()
            container.post {
                try {
                    // 目标：面板整体高度不超过屏幕的一半，并尽量保持面板中心贴近悬浮球中心
                    val dm = context.resources.displayMetrics
                    val maxPanelHeight = ((dm.heightPixels * 0.5f).toInt()).coerceAtLeast(dp(220))

                    // 首次测量后的容器高度，如超过上限则按超出量收缩 ScrollView 的高度
                    val overflow = (container.height - maxPanelHeight).coerceAtLeast(0)
                    if (overflow > 0) {
                        val lpScroll = scroll.layoutParams as LinearLayout.LayoutParams
                        val current = if (scroll.height > 0) scroll.height else maxPanelHeight
                        val newH = (current - overflow).coerceAtLeast(dp(120))
                        lpScroll.height = newH
                        scroll.layoutParams = lpScroll
                    }

                    // 第二帧：在最终高度生效后再以“中心对齐”进行定位，避免看起来偏上或偏下
                    container.post {
                        try {
                            positionContainer(container, centerX, centerY, isLeft)
                            container.animate().alpha(1f).translationX(0f).setDuration(160).start()
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to finalize position for text panel", e)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to limit and position text panel", e)
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.START

            windowManager.addView(root, params)
            return root
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show scrollable text panel", e)
            return null
        }
    }

    /**
     * 隐藏菜单
     */
    fun hideMenu(menuView: View?) {
        menuView?.let { v ->
            try {
                cancelAllAnimations(v)
                windowManager.removeView(v)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to remove menu view", e)
            }
        }
    }

    // ==================== 私有辅助方法 ====================

    private fun buildCapsule(iconRes: Int, label: String, cd: String, onClick: () -> Unit): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.ripple_capsule)
            val p = dp(10)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            contentDescription = cd
            setOnClickListener {
                hapticFeedback(this)
                onClick()
            }
        }
        val iv = ImageView(context).apply {
            setImageResource(iconRes)
            try {
                setColorFilter(UiColors.panelFg(context))
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to set icon color filter", e)
            }
        }
        val tv = TextView(context).apply {
            text = label
            setTextColor(UiColors.panelFg(context))
            textSize = 12f
            setPadding(dp(6), 0, 0, 0)
        }
        layout.addView(iv, LinearLayout.LayoutParams(dp(18), dp(18)))
        layout.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return layout
    }

    private fun positionContainer(
        container: View,
        centerX: Int,
        centerY: Int,
        isLeft: Boolean,
    ) {
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val offset = dp(16)
        val w = container.width
        val h = container.height
        val lpC = container.layoutParams as android.widget.FrameLayout.LayoutParams
        val left = if (isLeft) (centerX + offset) else (centerX - offset - w)
        val top = centerY - h / 2
        lpC.leftMargin = left.coerceIn(0, (screenW - w).coerceAtLeast(0))
        lpC.topMargin = top.coerceIn(0, (screenH - h).coerceAtLeast(0))
        container.layoutParams = lpC
    }

    private fun cancelAllAnimations(view: View) {
        try {
            view.animate().cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel view animation", e)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                cancelAllAnimations(view.getChildAt(i))
            }
        }
    }

    private fun hapticFeedback(view: View) {
        HapticFeedbackHelper.performTap(context, prefs, view)
    }

    private fun dp(v: Int): Int {
        val d = context.resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
