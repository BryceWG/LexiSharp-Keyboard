package com.brycewg.asrkb.ui

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.brycewg.asrkb.store.Prefs

/**
 * 无障碍服务,用于悬浮球语音识别后将文本插入到当前焦点的输入框中
 */
class AsrAccessibilityService : AccessibilityService() {

    /**
     * 焦点输入框上下文：用于在悬浮球语音识别期间进行“前缀 + 预览 + 后缀”的拼接写入。
     * - prefix：选区前文本
     * - suffix：选区后文本
     */
    data class FocusContext(
        val prefix: String,
        val suffix: String
    )

    companion object {
        private const val TAG = "AsrAccessibilityService"
        const val ACTION_INSERT_TEXT = "com.brycewg.asrkb.action.INSERT_TEXT"
        const val EXTRA_TEXT = "text"

        private var instance: AsrAccessibilityService? = null
        @Volatile private var overlayImeInsetsVisible: Boolean = false
        @Volatile private var overlayImeLayoutVisible: Boolean = false
        @Volatile private var overlayImeSignalsAt: Long = 0L

        fun isEnabled(): Boolean = instance != null

        /**
         * 悬浮层上报的 IME 可见性信号（insets/layout 推断）。
         * 仅在“兼容模式”下作为辅助信号使用。
         */
        fun reportOverlayImeVisibility(insetsVisible: Boolean?, layoutVisible: Boolean?) {
            try {
                insetsVisible?.let { overlayImeInsetsVisible = it }
                layoutVisible?.let { overlayImeLayoutVisible = it }
                overlayImeSignalsAt = System.currentTimeMillis()
            } catch (_: Throwable) { }
        }

        /**
         * 读取当前焦点可编辑节点的文本与选区，转换为前后缀快照；
         * 若无可编辑焦点，则返回 null（不进行预览）。
         */
        fun getCurrentFocusContext(): FocusContext? {
            val svc = instance ?: return null
            return try {
                val root = svc.rootInActiveWindow ?: return null
                val focused = svc.findFocusedEditableNode(root)
                if (focused != null) {
                    // 抓取当前输入框完整文本作为“全量文本”，用于基于选区切分前后缀
                    val full = focused.text?.toString() ?: ""
                    val selStart = focused.textSelectionStart.takeIf { it >= 0 } ?: full.length
                    val selEnd = focused.textSelectionEnd.takeIf { it >= 0 } ?: full.length
                    val start = selStart.coerceIn(0, full.length)
                    val end = selEnd.coerceIn(0, full.length)
                    val s = minOf(start, end)
                    val e = maxOf(start, end)
                    @Suppress("DEPRECATION")
                    focused.recycle()
                    FocusContext(
                        prefix = full.substring(0, s),
                        suffix = full.substring(e, full.length)
                    )
                } else null
            } catch (_: Throwable) { null }
        }

        fun insertText(context: Context, text: String): Boolean {
            Log.d(TAG, "insertText called with: $text, service enabled: ${instance != null}")
            val service = instance
            if (service == null) {
                // 无障碍服务未启用,复制到剪贴板
                Log.w(TAG, "Accessibility service not enabled, copying to clipboard")
                copyToClipboard(context, text)
                Toast.makeText(context, context.getString(com.brycewg.asrkb.R.string.floating_asr_copied), Toast.LENGTH_SHORT).show()
                return false
            }

            return service.performInsertText(text)
        }

        /**
         * 静默写入文本：不弹 Toast、不复制到剪贴板（用于中间结果预览）。
         * 返回是否写入成功。
         */
        fun insertTextSilent(text: String): Boolean {
            val service = instance ?: return false
            return service.performInsertTextSilent(text)
        }
        
        /**
         * 获取当前活动窗口的包名（尽力而为）。
         */
        fun getActiveWindowPackage(): String? {
            val service = instance ?: return null
            return try {
                // 优先 rootInActiveWindow
                val root = service.rootInActiveWindow
                val pkg = root?.packageName?.toString()
                if (!pkg.isNullOrEmpty()) return pkg

                // 回退：遍历窗口，取 active/focused 的 root 包名
                val ws = service.windows
                if (ws != null) {
                    for (w in ws) {
                        try {
                            if (w == null) continue
                            if (w.isActive || w.isFocused) {
                                val r = w.root
                                val p = r?.packageName?.toString()
                                if (!p.isNullOrEmpty()) return p
                            }
                        } catch (_: Throwable) { }
                    }
                }
                null
            } catch (_: Throwable) { null }
        }

        /**
         * 静默粘贴文本：临时放入剪贴板并对焦点输入框执行 PASTE 动作。
         * 成功返回 true，不弹 Toast、不修改用户可见状态。
         */
        fun pasteTextSilent(text: String): Boolean {
            val service = instance ?: return false
            return service.performPasteTextSilent(text)
        }

        /**
         * 选中全部并以粘贴方式替换为给定文本（静默）。
         * - 备份并恢复剪贴板；
         * - 尝试设置选区为[0, 当前文本长度]，失败则回退到大范围；
         * - 粘贴完成后尝试把光标移到末尾。
         */
        fun selectAllAndPasteSilent(text: String): Boolean {
            val service = instance ?: return false
            return service.performSelectAllAndPasteSilent(text)
        }
        
        private fun copyToClipboard(context: Context, text: String) {
            try {
                val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ASR Result", text)
                clipboard.setPrimaryClip(clip)
            } catch (_: Throwable) { }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        windowManager = try { getSystemService(WindowManager::class.java) } catch (_: Throwable) { null }
        // 刚连接时推送一次当前输入场景状态，并尝试附加 overlay 监听
        try { handler.post {
            tryAttachOverlayDetectorsIfNeeded()
            tryDispatchImeVisibilityHint()
        } } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
        try { detachOverlayDetectors() } catch (_: Throwable) { }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCheck = false
    private var lastImeSceneActive: Boolean? = null
    private var lastEditableFocusAt: Long = 0L
    private val holdAfterFocusMs: Long = 1200L
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 现用于辅助判断“仅在输入法面板显示时显示悬浮球”的场景
        // 为避免频繁遍历树，做轻量节流
        if (event == null) return
        val type = event.eventType
        // 扩大触发范围：窗口变化/内容变化/焦点变化/选区变化/文本变化/窗口集合变化
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return
        }
        if (!pendingCheck) {
            pendingCheck = true
            handler.postDelayed({
                pendingCheck = false
                tryDispatchImeVisibilityHint()
            }, 120)
        }
    }

    private fun tryDispatchImeVisibilityHint() {
        val prefs = try { Prefs(this) } catch (_: Throwable) { null } ?: return
        if (!prefs.floatingSwitcherOnlyWhenImeVisible) return
        val anyFloatingEnabled = prefs.floatingSwitcherEnabled || prefs.floatingAsrEnabled
        if (!anyFloatingEnabled) return
        // 兼容模式：根据设置动态附加或移除 overlay insets 监听
        tryAttachOverlayDetectorsIfNeeded()

        // 优先：是否存在输入法窗口（更接近“键盘显示”）
        val now = System.currentTimeMillis()
        val hasFocus = hasEditableFocusNow()
        if (hasFocus) lastEditableFocusAt = now
        val prefsCompat = try { Prefs(this).floatingImeVisibilityCompatEnabled } catch (_: Throwable) { false }
        val simpleVisible = isImeWindowVisible()
        val compatVisible = if (prefsCompat) {
            simpleVisible || hasFocus || isImePackageDetected() || isImeVisibleByInsets() || isImeVisibleByGlobalLayout()
        } else simpleVisible
        val active = compatVisible || (now - lastEditableFocusAt <= holdAfterFocusMs)
        val prev = lastImeSceneActive
        if (prev == null || prev != active) {
            lastImeSceneActive = active
            try {
                val action = if (active) FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE
                             else FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN
                // 通知输入法切换悬浮球
                try {
                    val i1 = Intent(this, FloatingImeSwitcherService::class.java).apply { this.action = action }
                    startService(i1)
                } catch (_: Throwable) { }
                // 通知语音识别悬浮球
                try {
                    val i2 = Intent(this, FloatingAsrService::class.java).apply { this.action = action }
                    startService(i2)
                } catch (_: Throwable) { }
            } catch (_: Throwable) { }
        }
    }

    override fun onInterrupt() {
        // 服务被中断
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_INSERT_TEXT) {
            val text = intent.getStringExtra(EXTRA_TEXT)
            if (!text.isNullOrEmpty()) {
                performInsertText(text)
            }
        }
        return START_NOT_STICKY
    }

    private fun performInsertText(text: String): Boolean {
        try {
            Log.d(TAG, "performInsertText called")
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.w(TAG, "Root node is null")
                copyToClipboard(this, text)
                Toast.makeText(this, getString(com.brycewg.asrkb.R.string.floating_asr_copied), Toast.LENGTH_SHORT).show()
                return false
            }

            val target = findFocusedEditableNode(rootNode)

            if (target != null) {
                Log.d(TAG, "Found editable/focusable node; trying ACTION_SET_TEXT")
                // 先尝试 ACTION_SET_TEXT 直接写入
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                // 保障焦点在目标上
                try { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) { }
                val setOk = try { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) } catch (_: Throwable) { false }
                if (setOk) {
                    @Suppress("DEPRECATION")
                    target.recycle()
                    Log.d(TAG, "ACTION_SET_TEXT success")
                    Toast.makeText(this, getString(com.brycewg.asrkb.R.string.floating_asr_inserted), Toast.LENGTH_SHORT).show()
                    return true
                }

                Log.w(TAG, "ACTION_SET_TEXT failed; try clipboard paste fallback")
                val pasteOk = performPasteFallback(target, text)
                @Suppress("DEPRECATION")
                target.recycle()
                if (pasteOk) {
                    Log.d(TAG, "Paste fallback success")
                    Toast.makeText(this, getString(com.brycewg.asrkb.R.string.floating_asr_inserted), Toast.LENGTH_SHORT).show()
                    return true
                }
            } else {
                Log.w(TAG, "No focused editable-like node found")
            }

            // 兜底：复制剪贴板
            copyToClipboard(this, text)
            Toast.makeText(this, getString(com.brycewg.asrkb.R.string.floating_asr_copied), Toast.LENGTH_SHORT).show()
            return false
        } catch (e: Throwable) {
            // 发生错误,复制到剪贴板
            Log.e(TAG, "Error inserting text", e)
            copyToClipboard(this, text)
            Toast.makeText(this, getString(com.brycewg.asrkb.R.string.floating_asr_copied), Toast.LENGTH_SHORT).show()
            return false
        }
    }

    // 静默版本：仅尝试设置文本，不做 Toast/复制
    private fun performInsertTextSilent(text: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            val focusedNode = findFocusedEditableNode(rootNode)
            if (focusedNode != null) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                @Suppress("DEPRECATION")
                focusedNode.recycle()
                success
            } else false
        } catch (_: Throwable) { false }
    }

    // 静默粘贴：使用剪贴板 + ACTION_PASTE，尽量不干扰用户当前剪贴板内容
    private fun performPasteTextSilent(text: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            val focusedNode = findFocusedEditableNode(rootNode) ?: return false

            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val previous = try { clipboard.primaryClip } catch (_: Throwable) { null }

            val clip = ClipData.newPlainText("ASR Paste", text)
            clipboard.setPrimaryClip(clip)

            val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            @Suppress("DEPRECATION")
            focusedNode.recycle()

            // 恢复之前的剪贴板（尽最大努力）；若没有之前内容则尝试清空
            try {
                if (previous != null) {
                    clipboard.setPrimaryClip(previous)
                } else {
                    try { clipboard.clearPrimaryClip() } catch (_: Throwable) { }
                }
            } catch (_: Throwable) {
                try { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) } catch (_: Throwable) { }
            }

            ok
        } catch (_: Throwable) { false }
    }

    // 选中全部并以“粘贴”方式替换文本（静默）
    private fun performSelectAllAndPasteSilent(text: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            val target = findFocusedEditableNode(rootNode) ?: return false

            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val previous = try { clipboard.primaryClip } catch (_: Throwable) { null }

            val len = try {
                val full = target.text?.toString() ?: ""
                full.length
            } catch (_: Throwable) { 0 }

            // 先尽量聚焦
            try { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) { }
            // 选中全部（若失败，后续直接尝试粘贴也可能覆盖）
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, if (len > 0) len else Int.MAX_VALUE)
            }
            try { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) } catch (_: Throwable) { }

            // 设置剪贴板并粘贴
            val clip = ClipData.newPlainText("ASR PasteAll", text)
            clipboard.setPrimaryClip(clip)
            var ok = try { target.performAction(AccessibilityNodeInfo.ACTION_PASTE) } catch (_: Throwable) { false }
            if (!ok) {
                // 回退：长按后再粘贴一次
                try { target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } catch (_: Throwable) { }
                Thread.sleep(100)
                ok = try { target.performAction(AccessibilityNodeInfo.ACTION_PASTE) } catch (_: Throwable) { false }
            }

            // 粘贴后把光标尽量移到末尾
            if (ok) {
                val endSel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                }
                try { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, endSel) } catch (_: Throwable) { }
            }

            // 恢复剪贴板（或清空）
            try {
                if (previous != null) {
                    clipboard.setPrimaryClip(previous)
                } else {
                    try { clipboard.clearPrimaryClip() } catch (_: Throwable) { }
                }
            } catch (_: Throwable) {
                try { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) } catch (_: Throwable) { }
            }

            @Suppress("DEPRECATION")
            target.recycle()
            ok
        } catch (_: Throwable) { false }
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) 优先取焦点节点且满足“可编辑或类名含 EditText 或支持 setText/paste”
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { f ->
            if (isEditableLike(f)) return f
            @Suppress("DEPRECATION") f.recycle()
        }
        // 2) 递归寻找“已聚焦且可编辑样式”的节点
        return findEditableNodeRecursive(root)
    }

    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && isEditableLike(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeRecursive(child)
            if (result != null) {
                @Suppress("DEPRECATION") child.recycle()
                return result
            }
            @Suppress("DEPRECATION") child.recycle()
        }
        return null
    }

    private fun isEditableLike(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: ""
        if (cls.contains("EditText", ignoreCase = true)) return true
        if (nodeHasAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return true
        if (nodeHasAction(node, AccessibilityNodeInfo.ACTION_PASTE)) return true
        return false
    }

    private fun nodeHasAction(node: AccessibilityNodeInfo, action: Int): Boolean {
        return try {
            val list = node.actionList
            list?.any { it.id == action } == true
        } catch (_: Throwable) { false }
    }

    private fun performPasteFallback(target: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            // 将文本放入剪贴板（带备份并恢复）
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val previous = try { clipboard.primaryClip } catch (_: Throwable) { null }
            val clip = ClipData.newPlainText("ASR PasteFallback", text)
            try { clipboard.setPrimaryClip(clip) } catch (_: Throwable) { }
            // 确保焦点在输入框
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // 若可长按，尝试长按以唤出粘贴菜单（部分应用要求）
            target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            // 尝试直接执行粘贴动作（多数输入框支持）
            var ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!ok) {
                // 延迟再试一次，等待长按菜单弹出
                Thread.sleep(120)
                ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            // 恢复剪贴板
            try {
                if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
            } catch (_: Throwable) { }
            ok
        } catch (_: Throwable) { false }
    }

    private fun hasEditableFocusNow(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val ok = focused.isEditable || (focused.className?.toString()?.contains("EditText", true) == true)
                @Suppress("DEPRECATION")
                focused.recycle()
                if (ok) return true
            }
            // 回退：遍历查找是否存在可编辑且聚焦的节点
            val found = findEditableNodeRecursive(root)
            @Suppress("DEPRECATION")
            root.recycle()
            found != null
        } catch (_: Throwable) { false }
    }

    private fun isImeWindowVisible(): Boolean {
        return try {
            val ws = windows ?: return false
            var visible = false
            for (w in ws) {
                try {
                    if (w?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        visible = true
                        break
                    }
                } catch (_: Throwable) { }
            }
            visible
        } catch (_: Throwable) { false }
    }

    private fun isImePackageDetected(): Boolean {
        return try {
            val ws = windows ?: return false
            for (w in ws) {
                try {
                    if (w?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val pkg = w.root?.packageName?.toString()
                        if (!pkg.isNullOrEmpty()) return true
                    }
                } catch (_: Throwable) { }
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun isImeVisibleByInsets(): Boolean {
        val ts = overlayImeSignalsAt
        if (ts <= 0L) return false
        val fresh = (System.currentTimeMillis() - ts) <= 2000L
        return fresh && overlayImeInsetsVisible
    }

    private fun isImeVisibleByGlobalLayout(): Boolean {
        val ts = overlayImeSignalsAt
        if (ts <= 0L) return false
        val fresh = (System.currentTimeMillis() - ts) <= 2000L
        return fresh && overlayImeLayoutVisible
    }

    private fun tryAttachOverlayDetectorsIfNeeded() {
        val prefs = try { Prefs(this) } catch (_: Throwable) { null } ?: return
        val need = prefs.floatingSwitcherOnlyWhenImeVisible && prefs.floatingImeVisibilityCompatEnabled
        if (!need) { detachOverlayDetectors(); return }
        if (overlayView != null) return
        try {
            val wm = windowManager ?: return
            val v = View(this)
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                1, 1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.title = "AsrImeInsetsProbe"
            lp.x = 0
            lp.y = 0
            v.setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val visible = try { insets.isVisible(android.view.WindowInsets.Type.ime()) } catch (_: Throwable) { false }
                    reportOverlayImeVisibility(visible, null)
                }
                insets
            }
            wm.addView(v, lp)
            overlayView = v
            try { v.requestApplyInsets() } catch (_: Throwable) { }
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val wi = v.rootWindowInsets
                    val vis = wi?.isVisible(android.view.WindowInsets.Type.ime()) ?: false
                    reportOverlayImeVisibility(vis, null)
                }
            } catch (_: Throwable) { }
        } catch (e: Throwable) {
            Log.w(TAG, "attach overlay insets probe failed", e)
        }
    }

    private fun detachOverlayDetectors() {
        val v = overlayView ?: return
        try { windowManager?.removeView(v) } catch (_: Throwable) { }
        overlayView = null
        try { reportOverlayImeVisibility(false, false) } catch (_: Throwable) { }
    }
}
