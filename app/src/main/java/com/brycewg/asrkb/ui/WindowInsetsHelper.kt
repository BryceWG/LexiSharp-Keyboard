package com.brycewg.asrkb.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlin.math.max

/**
 * Window Insets 处理工具类
 * 用于适配 Android 15 的边缘到边缘显示
 */
object WindowInsetsHelper {

    /**
     * 为根视图应用系统栏 insets
     *
     * @param rootView 根视图
     * @param applyTop 是否为顶部添加状态栏高度的 padding
     * @param applyBottom 是否为底部添加导航栏高度的 padding
     */
    fun applySystemBarsInsets(
        rootView: View,
        applyTop: Boolean = true,
        applyBottom: Boolean = true,
    ) {
        val initialPadding = Rect(
            rootView.paddingLeft,
            rootView.paddingTop,
            rootView.paddingRight,
            rootView.paddingBottom,
        )

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, windowInsets ->
            findActivity(v.context)?.window?.let { window ->
                WindowCompat.getInsetsController(window, v).isAppearanceLightStatusBars =
                    !isNightMode(v.context)
            }

            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(sysBars.bottom, ime.bottom)

            v.updatePadding(
                top = if (applyTop) initialPadding.top + sysBars.top else initialPadding.top,
                bottom = if (applyBottom) initialPadding.bottom + bottomInset else initialPadding.bottom,
                left = initialPadding.left + sysBars.left,
                right = initialPadding.right + sysBars.right,
            )

            windowInsets
        }

        ViewCompat.requestApplyInsets(rootView)
    }

    /**
     * 为 Toolbar 应用顶部状态栏 insets
     */
    fun applyTopInsets(view: View) {
        val initialPadding = Rect(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            view.paddingBottom,
        )

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = initialPadding.top + insets.top,
            )
            windowInsets
        }
    }

    /**
     * 为底部视图应用导航栏 insets
     */
    fun applyBottomInsets(view: View) {
        val initialPadding = Rect(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            view.paddingBottom,
        )

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(sysBars.bottom, ime.bottom)
            v.updatePadding(
                bottom = initialPadding.bottom + bottomInset,
            )
            windowInsets
        }
    }

    private fun isNightMode(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )
}
