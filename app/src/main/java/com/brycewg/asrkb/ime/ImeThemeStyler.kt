/**
 * IME View 主题色与系统栏同步工具。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BibiViewThemes

internal class ImeThemeStyler(private val prefs: Prefs) {

    fun applyKeyboardBackgroundColor(root: View) {
        ImeKeyboardViewFactory.applyKeyboardPanelBackground(root, prefs, floating = false)
    }

    fun resolveKeyboardBackgroundColor(ctx: Context): Int = BibiViewThemes.resolve(ctx, prefs).keyboardBackground

    fun syncSystemBarsToKeyboardBackground(
        window: Window,
        anchorView: View?,
        ctx: Context = window.context
    ) {
        val color = resolveKeyboardBackgroundColor(ctx)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.navigationBarColor = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val isLight = ColorUtils.calculateLuminance(color) > 0.5
        val controller = WindowInsetsControllerCompat(window, anchorView ?: window.decorView)
        controller.isAppearanceLightNavigationBars = isLight
    }

    fun installKeyboardInsetsListener(
        rootView: View,
        onSystemBarsBottomInsetChanged: (bottom: Int) -> Unit
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
            val bottom = ImeInsetsResolver.resolveBottomInset(windowInsets, rootView.resources)
            onSystemBarsBottomInsetChanged(bottom)
            windowInsets
        }
    }
}
