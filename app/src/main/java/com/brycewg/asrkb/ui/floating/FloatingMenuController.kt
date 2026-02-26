package com.brycewg.asrkb.ui.floating

import android.view.View
import com.brycewg.asrkb.ui.floatingball.FloatingMenuHelper

internal class FloatingMenuController(
    private val menuHelper: FloatingMenuHelper,
) {
    private var radialMenuView: View? = null
    private var vendorMenuView: View? = null
    private var radialDragSession: FloatingMenuHelper.DragRadialMenuSession? = null

    fun isForceVisibleMenuActive(): Boolean {
        return radialMenuView != null || vendorMenuView != null || radialDragSession != null
    }

    fun isDragSessionActive(): Boolean = radialDragSession != null

    fun showRadialMenu(
        anchorCenter: Pair<Int, Int>,
        alpha: Float,
        items: List<FloatingMenuHelper.MenuItem>,
        onDismiss: () -> Unit,
    ) {
        if (radialMenuView != null) return
        radialMenuView = menuHelper.showRadialMenu(anchorCenter, alpha, items) {
            radialMenuView = null
            onDismiss()
        }
    }

    fun showRadialMenuForDrag(
        anchorCenter: Pair<Int, Int>,
        alpha: Float,
        items: List<FloatingMenuHelper.MenuItem>,
        onDismiss: () -> Unit,
    ) {
        if (radialDragSession != null) return
        radialDragSession = menuHelper.showRadialMenuForDrag(anchorCenter, alpha, items) {
            radialMenuView = null
            radialDragSession = null
            onDismiss()
        }
        radialMenuView = radialDragSession?.root
    }

    fun updateDragHover(rawX: Float, rawY: Float) {
        radialDragSession?.updateHover(rawX, rawY)
    }

    fun performDragSelectionAt(rawX: Float, rawY: Float) {
        radialDragSession?.performSelectionAt(rawX, rawY)
        radialDragSession = null
    }

    fun dismissDragSession() {
        radialDragSession?.dismiss()
        radialDragSession = null
        radialMenuView = null
    }

    fun showListPanel(
        anchorCenter: Pair<Int, Int>,
        alpha: Float,
        title: String,
        entries: List<Triple<String, Boolean, () -> Unit>>,
        onDismiss: () -> Unit,
    ) {
        hideVendorMenu()
        vendorMenuView = menuHelper.showListPanel(anchorCenter, alpha, title, entries) {
            vendorMenuView = null
            onDismiss()
        }
    }

    fun showScrollableTextPanel(
        anchorCenter: Pair<Int, Int>,
        alpha: Float,
        title: String,
        texts: List<String>,
        onItemClick: (String) -> Unit,
        initialVisibleCount: Int,
        loadMoreCount: Int,
        onDismiss: () -> Unit,
    ) {
        hideVendorMenu()
        vendorMenuView = menuHelper.showScrollableTextPanel(
            anchorCenter,
            alpha,
            title,
            texts,
            onItemClick,
            initialVisibleCount,
            loadMoreCount,
        ) {
            vendorMenuView = null
            onDismiss()
        }
    }

    fun hideRadialMenu() {
        menuHelper.hideMenu(radialMenuView)
        radialMenuView = null
    }

    fun hideVendorMenu() {
        menuHelper.hideMenu(vendorMenuView)
        vendorMenuView = null
    }

    fun hideAll() {
        dismissDragSession()
        hideRadialMenu()
        hideVendorMenu()
    }
}
