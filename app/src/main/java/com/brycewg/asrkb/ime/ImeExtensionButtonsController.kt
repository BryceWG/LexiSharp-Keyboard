package com.brycewg.asrkb.ime

import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

internal class ImeExtensionButtonsController(
    private val prefs: Prefs,
    private val views: ImeViewRefs,
    private val actionHandler: KeyboardActionHandler,
    private val inputConnectionProvider: () -> android.view.inputmethod.InputConnection?,
    private val performKeyHaptic: (View?) -> Unit,
    private val moveCursorBy: (Int) -> Unit,
    private val toggleSelectionMode: () -> Unit,
    private val updateSelectExtButtonsUi: () -> Unit,
    private val showNumpadPanel: () -> Unit,
    private val showClipboardPanel: () -> Unit,
    private val hideKeyboardPanel: () -> Unit,
) {
    fun bindListeners() {
        // 中央扩展按钮（占位，暂无功能）
        views.btnExtCenter1?.setOnClickListener { v ->
            performKeyHaptic(v)
            // TODO: 添加具体功能
        }
        views.btnExtCenter2?.setOnClickListener { v ->
            performKeyHaptic(v)
            if (actionHandler.getCurrentState() !is KeyboardState.Listening) {
                actionHandler.commitText(inputConnectionProvider(), " ")
            }
        }
    }

    fun applyConfig() {
        setupExtensionButton(views.btnExt1, prefs.extBtn1)
        setupExtensionButton(views.btnExt2, prefs.extBtn2)
        setupExtensionButton(views.btnExt3, prefs.extBtn3)
        setupExtensionButton(views.btnExt4, prefs.extBtn4)
        updateSelectExtButtonsUi()
        updateSilenceAutoStopExtButtonsUi()
    }

    private fun setupExtensionButton(btn: ImageButton?, action: ExtensionButtonAction) {
        if (btn == null) return

        // 设置图标
        btn.setImageResource(action.iconResId)

        // 清理旧监听，避免切换功能后残留触摸/点击逻辑导致误触发
        btn.setOnClickListener(null)
        btn.setOnTouchListener(null)

        // 根据动作类型设置行为
        when (action) {
            ExtensionButtonAction.NONE -> {
                btn.visibility = View.GONE
            }

            ExtensionButtonAction.CURSOR_LEFT, ExtensionButtonAction.CURSOR_RIGHT -> {
                // 光标移动需要长按连发
                btn.visibility = View.VISIBLE
                setupCursorButtonRepeat(btn, action)
            }

            else -> {
                // 普通按钮：点击即可
                btn.visibility = View.VISIBLE
                btn.setOnClickListener { v ->
                    performKeyHaptic(v)
                    handleExtensionButtonAction(action)
                }
            }
        }
    }

    private fun handleExtensionButtonAction(action: ExtensionButtonAction) {
        val result = actionHandler.handleExtensionButtonClick(action, inputConnectionProvider())

        when (result) {
            KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS -> {
                // 成功，不需要额外处理
            }

            KeyboardActionHandler.ExtensionButtonActionResult.FAILED -> {
                // 失败，已在 actionHandler 中处理
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_TOGGLE_SELECTION -> {
                // 在主界面直接切换选择模式（不进入 AI 编辑面板）
                toggleSelectionMode()
                // 同步扩展按钮（若配置为 SELECT）
                updateSelectExtButtonsUi()
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_NUMPAD -> {
                // 从主界面进入数字/符号面板
                showNumpadPanel()
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_CLIPBOARD -> {
                showClipboardPanel()
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_CURSOR_LEFT,
            KeyboardActionHandler.ExtensionButtonActionResult.NEED_CURSOR_RIGHT,
            -> {
                // 光标移动已在长按处理中完成
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_HIDE_KEYBOARD -> {
                hideKeyboardPanel()
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_TOGGLE_CONTINUOUS_TALK -> {
                applyConfig()
            }
        }

        if (action == ExtensionButtonAction.SILENCE_AUTOSTOP_TOGGLE &&
            result == KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        ) {
            updateSilenceAutoStopExtButtonsUi()
        }
    }

    private fun setupCursorButtonRepeat(btn: ImageButton, action: ExtensionButtonAction) {
        val initialDelay = 350L
        val repeatInterval = 50L
        var repeatRunnable: Runnable? = null

        btn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    // 立即移动一次
                    val delta = if (action == ExtensionButtonAction.CURSOR_LEFT) -1 else 1
                    moveCursorBy(delta)

                    // 设置连发
                    repeatRunnable?.let { v.removeCallbacks(it) }
                    val r = Runnable {
                        moveCursorBy(delta)
                        repeatRunnable?.let { v.postDelayed(it, repeatInterval) }
                    }
                    repeatRunnable = r
                    v.postDelayed(r, initialDelay)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatRunnable?.let { v.removeCallbacks(it) }
                    repeatRunnable = null
                    v.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun updateSilenceAutoStopExtButtonsUi() {
        val enabled = prefs.autoStopOnSilenceEnabled

        fun updateBtn(btn: ImageButton?, action: ExtensionButtonAction) {
            if (action == ExtensionButtonAction.SILENCE_AUTOSTOP_TOGGLE) {
                btn?.setImageResource(if (enabled) R.drawable.hand_palm_fill else R.drawable.hand_palm)
                btn?.isSelected = enabled
            }
        }

        updateBtn(views.btnExt1, prefs.extBtn1)
        updateBtn(views.btnExt2, prefs.extBtn2)
        updateBtn(views.btnExt3, prefs.extBtn3)
        updateBtn(views.btnExt4, prefs.extBtn4)
    }
}
