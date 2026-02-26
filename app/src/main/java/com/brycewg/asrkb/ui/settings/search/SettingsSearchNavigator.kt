/**
 * 设置搜索的跳转、滚动定位与高亮逻辑。
 *
 * 归属模块：ui/settings/search
 */
package com.brycewg.asrkb.ui.settings.search

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.brycewg.asrkb.UiColorTokens
import com.brycewg.asrkb.UiColors

object SettingsSearchNavigator {
    const val EXTRA_TARGET_VIEW_ID = "extra_target_view_id"
    const val EXTRA_HIGHLIGHT = "extra_highlight"
    const val EXTRA_FORCE_ASR_VENDOR_ID = "extra_force_asr_vendor_id"
    const val EXTRA_FORCE_LLM_VENDOR_ID = "extra_force_llm_vendor_id"

    fun <T : Activity> launch(
        source: Activity,
        activityClass: Class<T>,
        targetViewId: Int,
        highlight: Boolean = true,
        forceAsrVendorId: String? = null,
        forceLlmVendorId: String? = null,
    ) {
        val intent = Intent(source, activityClass).apply {
            putExtra(EXTRA_TARGET_VIEW_ID, targetViewId)
            putExtra(EXTRA_HIGHLIGHT, highlight)
            if (!forceAsrVendorId.isNullOrBlank()) {
                putExtra(EXTRA_FORCE_ASR_VENDOR_ID, forceAsrVendorId)
            }
            if (!forceLlmVendorId.isNullOrBlank()) {
                putExtra(EXTRA_FORCE_LLM_VENDOR_ID, forceLlmVendorId)
            }
        }
        source.startActivity(intent)
    }

    fun applyScrollAndHighlightIfNeeded(activity: Activity) {
        val intent = activity.intent ?: return
        val targetViewId = intent.getIntExtra(EXTRA_TARGET_VIEW_ID, View.NO_ID)
        if (targetViewId == View.NO_ID) return

        val target = activity.findViewById<View>(targetViewId)
        if (target == null) {
            intent.removeExtra(EXTRA_TARGET_VIEW_ID)
            intent.removeExtra(EXTRA_HIGHLIGHT)
            return
        }

        val root = activity.findViewById<View>(android.R.id.content) ?: target.rootView ?: return
        val scroller = findFirstScrollContainer(root)

        if (scroller != null) {
            scroller.post {
                scrollToTarget(scroller, target)
                val shouldHighlight = intent.getBooleanExtra(EXTRA_HIGHLIGHT, true)
                if (shouldHighlight) {
                    highlightOnce(target)
                }
                intent.removeExtra(EXTRA_TARGET_VIEW_ID)
                intent.removeExtra(EXTRA_HIGHLIGHT)
            }
        } else {
            val shouldHighlight = intent.getBooleanExtra(EXTRA_HIGHLIGHT, true)
            if (shouldHighlight) {
                target.post { highlightOnce(target) }
            }
            intent.removeExtra(EXTRA_TARGET_VIEW_ID)
            intent.removeExtra(EXTRA_HIGHLIGHT)
        }
    }

    private fun scrollToTarget(scroller: View, target: View) {
        val rect = Rect(0, 0, target.width, target.height)
        val vg = scroller as? ViewGroup ?: return
        vg.offsetDescendantRectToMyCoords(target, rect)
        val offsetPx = dpToPx(target, 12f)
        val y = (rect.top - offsetPx).coerceAtLeast(0)
        when (scroller) {
            is NestedScrollView -> scroller.smoothScrollTo(0, y)
            is ScrollView -> scroller.smoothScrollTo(0, y)
            else -> vg.scrollTo(0, y)
        }
    }

    private fun highlightOnce(target: View) {
        val base = UiColors.get(target, UiColorTokens.selectedBg)
        val drawable = ColorDrawable(ColorUtils.setAlphaComponent(base, 0))
        val old = target.background
        target.background = drawable

        val animator = android.animation.ValueAnimator.ofInt(0, 160, 0).apply {
            duration = 900L
            addUpdateListener { anim ->
                val a = anim.animatedValue as? Int ?: 0
                drawable.color = ColorUtils.setAlphaComponent(base, a.coerceIn(0, 255))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    target.background = old
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    target.background = old
                }
            })
        }
        animator.start()
    }

    private fun dpToPx(view: View, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            view.resources.displayMetrics,
        ).toInt()
    }

    private fun findFirstScrollContainer(root: View): View? {
        if (root is NestedScrollView || root is ScrollView) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstScrollContainer(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
