package com.brycewg.asrkb.ime

import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

internal class ImeLayoutController(
    private val prefs: Prefs,
    private val themeStyler: ImeThemeStyler,
    private val viewRefsProvider: () -> ImeViewRefs?,
) {
    private var rootView: View? = null
    private var systemNavBarBottomInset: Int = 0
    private var micBaseGroupHeight: Int = -1
    private var lastAppliedHeightScale: Float = 1.0f

    fun installKeyboardInsetsListener(rootView: View) {
        this.rootView = rootView
        themeStyler.installKeyboardInsetsListener(rootView) { bottom ->
            systemNavBarBottomInset = bottom
            applyKeyboardHeightScale()
        }
    }

    fun bindMicVerticalFix(views: ImeViewRefs) {
        micBaseGroupHeight = -1
        views.groupMicStatus?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h <= 0) return@addOnLayoutChangeListener
            if (micBaseGroupHeight < 0) {
                micBaseGroupHeight = h
                views.btnMic?.translationY = 0f
            } else {
                val delta = h - micBaseGroupHeight
                views.btnMic?.translationY = (delta / 2f)
            }
        }
    }

    fun applyKeyboardHeightScale() {
        val root = rootView ?: viewRefsProvider()?.rootView ?: return
        val refs = viewRefsProvider()

        val tier = prefs.keyboardHeightTier
        val scale = when (tier) {
            2 -> 1.15f
            3 -> 1.30f
            else -> 1.0f
        }

        fun dp(v: Float): Int {
            val d = root.resources.displayMetrics.density
            return (v * d + 0.5f).toInt()
        }

        // 若缩放等级发生变化，重置麦克风位移基线，避免基于旧高度的下移造成底部截断
        if (kotlin.math.abs(lastAppliedHeightScale - scale) > 1e-3f) {
            lastAppliedHeightScale = scale
            micBaseGroupHeight = -1
            refs?.btnMic?.translationY = 0f
        }

        // 同步一次当前 RootWindowInsets，避免首次缩放时 bottom inset 尚未写入导致底部裁剪
        run {
            val rw = ViewCompat.getRootWindowInsets(root)
            var b = if (rw != null) ImeInsetsResolver.resolveBottomInset(rw, root.resources) else 0
            if (b <= 0) {
                val decor = root.rootView
                b = decor.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            }
            if (b > 0) {
                systemNavBarBottomInset = b
            }
        }

        // 应用底部间距（无论是否缩放都需要）
        val fl = root as? FrameLayout
        if (fl != null) {
            val ps = fl.paddingStart
            val pe = fl.paddingEnd
            val pt = dp(8f * scale)
            val basePb = dp(12f * scale)
            // 添加用户设置的底部间距
            val extraPadding = dp(prefs.keyboardBottomPaddingDp.toFloat())
            // 添加系统导航栏高度以适配 Android 15 边缘到边缘显示
            val pb = basePb + extraPadding + systemNavBarBottomInset
            fl.setPaddingRelative(ps, pt, pe, pb)
        }

        // 顶部主行高度（无论是否缩放都需要重设，避免从大/中切回小时残留）
        run {
            val topRow = refs?.rowTop ?: root.findViewById(R.id.rowTop) as? ConstraintLayout
            if (topRow != null) {
                val lp = topRow.layoutParams
                lp.height = dp(80f * scale)
                topRow.layoutParams = lp
            }
        }

        // 扩展按钮行高度（同样需要在 scale==1 时恢复）
        run {
            val extRow = refs?.rowExtension ?: root.findViewById(R.id.rowExtension) as? ConstraintLayout
            if (extRow != null) {
                val lp = extRow.layoutParams
                lp.height = dp(50f * scale)
                extRow.layoutParams = lp
            }
        }

        // 使主键盘功能行（overlay）从顶部锚定，避免垂直居中导致的像素舍入抖动
        // 计算规则：rowExtension 完整高度 + rowTop 高度的一半 + 固定偏移
        // = 50s(rowExtension完整) + 40s(rowTop的一半) + 6 = 90s + 6
        run {
            val overlay = refs?.rowOverlay ?: root.findViewById(R.id.rowOverlay) as? ConstraintLayout
            val lp = overlay?.layoutParams as? FrameLayout.LayoutParams
            if (overlay != null && lp != null) {
                lp.topMargin = dp(90f * scale + 6f)
                lp.gravity = Gravity.TOP
                overlay.layoutParams = lp
            }
        }

        // 手势按钮覆盖层：定位到第二排第三排按钮的位置
        // 计算：rowExtension 高度 (50dp) 作为顶部偏移，使手势按钮与第二排顶部对齐
        run {
            val overlay = refs?.rowRecordingGestures ?: root.findViewById(R.id.rowRecordingGestures) as? ConstraintLayout
            val lp = overlay?.layoutParams as? FrameLayout.LayoutParams
            if (overlay != null && lp != null) {
                lp.topMargin = dp(50f * scale)
                lp.gravity = Gravity.TOP
                overlay.layoutParams = lp
            }
        }

        fun scaleSquareButton(id: Int) {
            val v = root.findViewById<View>(id) ?: return
            val lp = v.layoutParams
            lp.width = dp(40f * scale)
            lp.height = dp(40f * scale)
            v.layoutParams = lp
        }

        fun scaleGestureButton(v: View?) {
            val lp = v?.layoutParams as? ConstraintLayout.LayoutParams ?: return
            val baseSize = 86f * scale
            lp.width = dp(baseSize)
            lp.height = dp(baseSize)
            v.layoutParams = lp
        }

        fun scaleChildrenByTag(root: View?, tag: String) {
            if (root == null) return
            if (root is android.view.ViewGroup) {
                for (i in 0 until root.childCount) {
                    scaleChildrenByTag(root.getChildAt(i), tag)
                }
            }
            val t = root.tag as? String
            if (t == tag) {
                val lp = root.layoutParams
                lp.height = dp(40f * scale)
                // 宽度可能由权重控制，不强制写入
                root.layoutParams = lp
            }
        }

        val ids40 = intArrayOf(
            // 主键盘按钮
            R.id.btnHide,
            R.id.btnPostproc,
            R.id.btnBackspace,
            R.id.btnPromptPicker,
            R.id.btnSettings,
            R.id.btnImeSwitcher,
            R.id.btnEnter,
            R.id.btnAiEdit,
            R.id.btnPunct1,
            R.id.btnPunct2,
            R.id.btnPunct3,
            R.id.btnPunct4,
            // 扩展按钮
            R.id.btnExt1,
            R.id.btnExt2,
            R.id.btnExt3,
            R.id.btnExt4,
            // AI 编辑面板按钮
            R.id.btnAiPanelBack,
            R.id.btnAiPanelApplyPreset,
            R.id.btnAiPanelCursorLeft,
            R.id.btnAiPanelCursorRight,
            R.id.btnAiPanelNumpad,
            R.id.btnAiPanelSelect,
            R.id.btnAiPanelSelectAll,
            R.id.btnAiPanelCopy,
            R.id.btnAiPanelUndo,
            R.id.btnAiPanelPaste,
            R.id.btnAiPanelMoveStart,
            R.id.btnAiPanelMoveEnd,
            // 剪贴板面板按钮
            R.id.clip_btnBack,
            R.id.clip_btnDelete,
        )
        ids40.forEach { scaleSquareButton(it) }
        scaleGestureButton(refs?.btnGestureCancel)
        scaleGestureButton(refs?.btnGestureSend)

        // 缩放中央按钮（仅高度，宽度由约束控制）
        run {
            val v1: View? = refs?.btnExtCenter1 ?: root.findViewById(R.id.btnExtCenter1)
            if (v1 != null) {
                val lp = v1.layoutParams
                lp.height = dp(40f * scale)
                // 宽度由约束控制，不设置
                v1.layoutParams = lp
            }
        }

        run {
            val v2: View? = refs?.btnExtCenter2 ?: root.findViewById(R.id.btnExtCenter2)
            if (v2 != null) {
                val lp = v2.layoutParams
                lp.height = dp(40f * scale)
                // 宽度由约束控制，不设置
                v2.layoutParams = lp
            }
        }

        // 数字/标点小键盘的方形按键（通过 tag="key40" 统一缩放高度）
        scaleChildrenByTag(refs?.layoutNumpadPanel, "key40")

        // 数字/符号面板：按主键盘网格对齐，避免切换时上下错位
        run {
            val panel: View? = refs?.layoutNumpadPanel ?: root.findViewById(R.id.layoutNumpadPanel)
            if (panel != null) {
                val extRowHeightPx = dp(50f * scale)
                val keySizePx = dp(40f * scale)
                val topInsetPx = ((extRowHeightPx - keySizePx) / 2).coerceAtLeast(0)
                val gapPx = dp(6f)

                val ps = panel.paddingStart
                val pe = panel.paddingEnd
                val pb = panel.paddingBottom
                if (panel.paddingTop != topInsetPx) {
                    panel.setPaddingRelative(ps, topInsetPx, pe, pb)
                }

                fun updateLinearTopMargin(id: Int, topPx: Int) {
                    val v = panel.findViewById<View>(id) ?: return
                    val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
                    if (lp.topMargin == topPx) return
                    lp.topMargin = topPx
                    v.layoutParams = lp
                }

                fun updateLinearBottomMargin(id: Int, bottomPx: Int) {
                    val v = panel.findViewById<View>(id) ?: return
                    val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
                    if (lp.bottomMargin == bottomPx) return
                    lp.bottomMargin = bottomPx
                    v.layoutParams = lp
                }

                updateLinearBottomMargin(R.id.rowNumpadDigits, topInsetPx)
                updateLinearBottomMargin(R.id.rowPunct1, gapPx)
                updateLinearBottomMargin(R.id.rowPunct2, gapPx)
                updateLinearTopMargin(R.id.rowNumpadBottomBar, 0)
            }
        }

        // AI 编辑面板：按主键盘按钮行对齐（避免切换时按钮上下跳变）
        run {
            fun updateTopMargin(id: Int, topPx: Int) {
                val v = root.findViewById<View>(id) ?: return
                val lp = v.layoutParams as? ConstraintLayout.LayoutParams ?: return
                if (lp.topMargin == topPx) return
                lp.topMargin = topPx
                v.layoutParams = lp
            }

            val infoTop = dp(5f * scale)
            val row1Top = dp(50f * scale)
            val row2Top = dp(90f * scale + 6f)
            val row3Top = dp(130f * scale + 12f)

            // 信息栏：与主键盘第一行按钮对齐，且高度随缩放同步
            val info = root.findViewById<View>(R.id.aiEditInfoBar)
            val infoLp = info?.layoutParams as? ConstraintLayout.LayoutParams
            if (info != null && infoLp != null) {
                val h = dp(40f * scale)
                var changed = false
                if (infoLp.topMargin != infoTop) {
                    infoLp.topMargin = infoTop
                    changed = true
                }
                if (infoLp.height != h) {
                    infoLp.height = h
                    changed = true
                }
                if (changed) info.layoutParams = infoLp
            }

            // 空格键：与 AI 编辑面板第三行按钮对齐（对应主键盘第四行），且高度随缩放同步
            val space = root.findViewById<View>(R.id.btnAiPanelSpace)
            val spaceLp = space?.layoutParams as? ConstraintLayout.LayoutParams
            if (space != null && spaceLp != null) {
                val h = dp(40f * scale)
                var changed = false
                if (spaceLp.topMargin != row3Top) {
                    spaceLp.topMargin = row3Top
                    changed = true
                }
                if (spaceLp.height != h) {
                    spaceLp.height = h
                    changed = true
                }
                if (changed) space.layoutParams = spaceLp
            }

            // 三行按钮：分别与主键盘第 2/3/4 行按钮对齐
            updateTopMargin(R.id.aiEditRow1Left, row1Top)
            updateTopMargin(R.id.aiEditRow1Right, row1Top)
            updateTopMargin(R.id.aiEditRow2Left, row2Top)
            updateTopMargin(R.id.aiEditRow2Right, row2Top)
            updateTopMargin(R.id.aiEditRow3Left, row3Top)
            updateTopMargin(R.id.aiEditRow3Right, row3Top)
        }

        refs?.btnMic?.customSize = dp(72f * scale)

        // 调整麦克风容器的 translationY：使用常量位移，避免大比例时向下偏移过多导致底部裁剪
        refs?.groupMicStatus?.translationY = dp(3f).toFloat()
        // 确保麦克风容器在最上层，避免被其它 overlay 遮挡
        refs?.groupMicStatus?.bringToFront()
    }

    fun hasResolvedBottomInset(): Boolean = systemNavBarBottomInset > 0

    fun fixImeInsetsIfNeeded(
        imeViewVisible: Boolean,
        outInsets: InputMethodService.Insets,
        decorView: View?,
    ) {
        if (!imeViewVisible) return
        val input = rootView ?: viewRefsProvider()?.rootView ?: return
        val decor = decorView ?: return

        val decorH = decor.height
        val decorW = decor.width
        if (decorH <= 0 || decorW <= 0) return

        var inputH = input.height
        if (inputH <= 0) {
            // 视图尚未 layout 时，使用一次 measure 获取 wrap_content 目标高度
            try {
                val wSpec = View.MeasureSpec.makeMeasureSpec(decorW, View.MeasureSpec.EXACTLY)
                val hSpec = View.MeasureSpec.makeMeasureSpec(decorH, View.MeasureSpec.AT_MOST)
                input.measure(wSpec, hSpec)
                inputH = input.measuredHeight
            } catch (t: Throwable) {
                android.util.Log.w("AsrKeyboardService", "fixImeInsets measure failed", t)
                return
            }
        }
        if (inputH <= 0) return

        val beforeContentTop = outInsets.contentTopInsets
        val beforeVisibleTop = outInsets.visibleTopInsets

        val topByHeight = (decorH - inputH).coerceIn(0, decorH)
        var locationTop = -1
        run {
            try {
                val loc = IntArray(2)
                input.getLocationInWindow(loc)
                locationTop = loc[1]
            } catch (t: Throwable) {
                android.util.Log.w("AsrKeyboardService", "fixImeInsets getLocationInWindow failed", t)
            }
        }
        val top = if (locationTop in 0 until decorH) {
            minOf(locationTop, topByHeight)
        } else {
            topByHeight
        }

        val correctionThresholdPx = (decor.resources.displayMetrics.density * 2f + 0.5f).toInt().coerceAtLeast(1)
        val needsColdStartFix = beforeContentTop <= 0
        // 系统给出的 contentTopInsets 偏大时会导致宿主上移不足，出现输入框被键盘遮挡。
        val needsOverlapFix = beforeContentTop > top + correctionThresholdPx
        val needsFix = top > 0 && decorH > inputH && (needsColdStartFix || needsOverlapFix)
        if (!needsFix) return

        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        // 触摸区域限定为键盘区域，避免空白区域吞触摸
        outInsets.touchableRegion.set(0, top, decorW, decorH)

        DebugLogManager.log(
            category = "ime",
            event = "compute_insets_fix",
            data = mapOf(
                "decorH" to decorH,
                "decorW" to decorW,
                "inputH" to inputH,
                "beforeContentTop" to beforeContentTop,
                "beforeVisibleTop" to beforeVisibleTop,
                "topByHeight" to topByHeight,
                "locationTop" to locationTop,
                "needsColdStartFix" to needsColdStartFix,
                "needsOverlapFix" to needsOverlapFix,
                "correctionThresholdPx" to correctionThresholdPx,
                "afterTop" to top,
            ),
        )
    }
}
