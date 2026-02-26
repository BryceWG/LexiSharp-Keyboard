package com.brycewg.asrkb.ime

import android.content.Context
import android.view.View
import android.view.Window
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.brycewg.asrkb.UiColorTokens
import com.brycewg.asrkb.UiColors

internal class ImeThemeStyler {

    fun applyKeyboardBackgroundColor(root: View) {
        root.setBackgroundColor(resolveKeyboardBackgroundColor(root.context))
    }

    fun resolveKeyboardBackgroundColor(ctx: Context): Int {
        val baseSurface = UiColors.panelBg(ctx)
        val micContainer = UiColors.get(ctx, UiColorTokens.secondaryContainer)
        val scrim = UiColors.get(ctx, UiColorTokens.scrim)
        val mixed = ColorUtils.blendARGB(baseSurface, micContainer, 0.08f)
        return ColorUtils.blendARGB(mixed, scrim, 0.04f)
    }

    fun syncSystemBarsToKeyboardBackground(window: Window, anchorView: View?, ctx: Context = window.context) {
        val color = resolveKeyboardBackgroundColor(ctx)
        @Suppress("DEPRECATION")
        window.navigationBarColor = color
        val isLight = ColorUtils.calculateLuminance(color) > 0.5
        val controller = WindowInsetsControllerCompat(window, anchorView ?: window.decorView)
        controller.isAppearanceLightNavigationBars = isLight
    }

    fun installKeyboardInsetsListener(
        rootView: View,
        onSystemBarsBottomInsetChanged: (bottom: Int) -> Unit,
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
            val bottom = ImeInsetsResolver.resolveBottomInset(windowInsets, rootView.resources)
            onSystemBarsBottomInsetChanged(bottom)
            windowInsets
        }
    }
}
