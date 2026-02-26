package com.brycewg.asrkb.ime

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

internal class ImeMainKeyboardBinder(
    private val context: android.content.Context,
    private val prefs: Prefs,
    private val views: ImeViewRefs,
    private val inputHelper: InputConnectionHelper,
    private val actionHandler: KeyboardActionHandler,
    private val backspaceGestureHandler: BackspaceGestureHandler,
    private val performKeyHaptic: (View?) -> Unit,
    private val vibrateTick: () -> Unit,
    private val hasRecordAudioPermission: () -> Boolean,
    private val refreshPermissionUi: () -> Unit,
    private val clearStatusTextStyle: () -> Unit,
    private val showStatusMessage: (String) -> Unit,
    private val renderCurrentState: () -> Unit,
    private val showAiEditPanel: () -> Unit,
    private val showNumpadPanel: (returnToAiPanel: Boolean) -> Unit,
    private val showClipboardPanel: () -> Unit,
    private val openSettings: () -> Unit,
    private val showPromptPicker: (View) -> Unit,
    private val showVendorPicker: (View) -> Unit,
    private val onImeSwitchButtonClicked: () -> Unit,
    private val inputConnectionProvider: () -> android.view.inputmethod.InputConnection?,
    private val editorInfoProvider: () -> android.view.inputmethod.EditorInfo?,
) {
    private var keyHintResetRunnable: Runnable? = null

    fun bind() {
        bindTopRow()
        bindBackspace()
        bindMainRow()
        bindPunctuation()
        bindFixedButtonLongPressHints()
    }

    fun applyPunctuationLabels() {
        views.btnPunct2?.setTexts(prefs.punct1, prefs.punct2)
        views.btnPunct3?.setTexts(prefs.punct3, prefs.punct4)
    }

    fun updatePostprocIcon() {
        views.btnPostproc?.setImageResource(if (prefs.postProcessEnabled) R.drawable.magic_wand_fill else R.drawable.magic_wand)
    }

    private fun bindTopRow() {
        // 顶部左侧按钮（原 Prompt 切换）改为：进入 AI 编辑面板
        views.btnPromptPicker?.setOnClickListener { v ->
            performKeyHaptic(v)
            if (!hasRecordAudioPermission()) {
                refreshPermissionUi()
                return@setOnClickListener
            }
            if (!prefs.hasAsrKeys()) {
                clearStatusTextStyle()
                views.txtStatusText?.text = context.getString(R.string.hint_need_keys)
                return@setOnClickListener
            }
            if (!prefs.hasLlmKeys()) {
                clearStatusTextStyle()
                views.txtStatusText?.text = context.getString(R.string.hint_need_llm_keys)
                return@setOnClickListener
            }
            showAiEditPanel()
        }

        // 顶部行：后处理开关（魔杖）
        views.btnPostproc?.apply {
            updatePostprocIcon()
            setOnClickListener { v ->
                performKeyHaptic(v)
                actionHandler.handlePostprocessToggle()
                updatePostprocIcon()
            }
        }
    }

    private fun bindBackspace() {
        // 退格按钮（委托给手势处理器）
        views.btnBackspace?.setOnClickListener { v ->
            performKeyHaptic(v)
            inputHelper.sendBackspace(inputConnectionProvider())
        }

        views.btnBackspace?.setOnTouchListener { v, event ->
            backspaceGestureHandler.handleTouchEvent(v, event, inputConnectionProvider())
        }

        // 设置退格手势监听器
        backspaceGestureHandler.setListener(object : BackspaceGestureHandler.Listener {
            override fun onSingleDelete() {
                actionHandler.saveUndoSnapshot(inputConnectionProvider())
                inputHelper.sendBackspace(inputConnectionProvider())
            }

            override fun onClearAll() {
                // 强制以清空前的文本作为撤销快照
                actionHandler.saveUndoSnapshot(inputConnectionProvider(), force = true)
            }

            override fun onUndo() {
                actionHandler.handleUndo(inputConnectionProvider())
            }

            override fun onVibrateRequest() {
                vibrateTick()
            }
        })
    }

    private fun bindMainRow() {
        views.btnSettings?.setOnClickListener { v ->
            performKeyHaptic(v)
            openSettings()
        }

        views.btnEnter?.setOnClickListener { v ->
            performKeyHaptic(v)
            inputHelper.sendEnter(inputConnectionProvider(), editorInfoProvider())
        }

        views.btnHide?.setOnClickListener { v ->
            performKeyHaptic(v)
            showClipboardPanel()
        }

        // 覆盖行按钮：Prompt 选择（article）
        views.btnImeSwitcher?.setOnClickListener { v ->
            performKeyHaptic(v)
            showPromptPicker(v)
        }

        // 中间功能行按钮（现为键盘切换）
        views.btnAiEdit?.setOnClickListener { v ->
            performKeyHaptic(v)
            onImeSwitchButtonClicked()
        }
    }

    private fun bindPunctuation() {
        // 第一个标点按钮替换为数字/符号键盘入口（普通按钮）
        views.btnPunct1?.setOnClickListener { v ->
            performKeyHaptic(v)
            showNumpadPanel(false)
        }

        // 左侧合并标点键（1/2）
        views.btnPunct2?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(inputConnectionProvider(), prefs.punct1)
        }
        views.btnPunct2?.setOnTouchListener(
            createSwipeUpToAltListener(
                primary = { prefs.punct1 },
                secondary = { prefs.punct2 },
            ),
        )

        // 右侧合并标点键（3/4）
        views.btnPunct3?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(inputConnectionProvider(), prefs.punct3)
        }
        views.btnPunct3?.setOnTouchListener(
            createSwipeUpToAltListener(
                primary = { prefs.punct3 },
                secondary = { prefs.punct4 },
            ),
        )

        // 第四个按键：供应商切换按钮（样式与 Prompt 选择类似）
        views.btnPunct4?.setOnClickListener { v ->
            performKeyHaptic(v)
            showVendorPicker(v)
        }
    }

    private fun bindFixedButtonLongPressHints() {
        // 仅支持主键盘固定功能按钮，不处理扩展按钮，也不处理回车/退格
        registerLongPressHint(views.btnPromptPicker, R.string.cd_ai_edit)
        registerLongPressHint(views.btnPostproc, R.string.cd_postproc_toggle)
        registerLongPressHint(views.btnHide, R.string.ext_btn_clipboard)
        registerLongPressHint(views.btnSettings, R.string.cd_settings)
        registerLongPressHint(views.btnImeSwitcher, R.string.cd_prompt_picker)
        registerLongPressHint(views.btnAiEdit, R.string.cd_switch_ime)
        registerLongPressHint(views.btnPunct1, R.string.cd_numpad)
        registerLongPressHint(views.btnPunct4, R.string.cd_vendor_picker)
    }

    private fun registerLongPressHint(view: View?, @StringRes hintRes: Int) {
        view?.setOnLongClickListener { v ->
            performKeyHaptic(v)
            showFunctionHint(hintRes)
            true
        }
    }

    private fun showFunctionHint(@StringRes hintRes: Int) {
        val statusView = views.txtStatusText ?: return
        clearStatusTextStyle()
        showStatusMessage(context.getString(hintRes))
        keyHintResetRunnable?.let(statusView::removeCallbacks)
        val restoreRunnable = Runnable { renderCurrentState() }
        keyHintResetRunnable = restoreRunnable
        statusView.postDelayed(restoreRunnable, 1500L)
    }

    /**
     * 创建“上滑触发次符号”的触摸监听：
     * - ACTION_UP 时根据位移决定输入主/次符号
     */
    private fun createSwipeUpToAltListener(
        primary: () -> String,
        secondary: () -> String,
    ): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val thresholdPx = (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(touchSlop)
        var downY = 0f
        return View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y
                    false
                }

                MotionEvent.ACTION_UP -> {
                    val dy = downY - ev.y
                    if (dy >= thresholdPx) {
                        // 上滑：输入次符号
                        performKeyHaptic(v)
                        actionHandler.commitText(inputConnectionProvider(), secondary())
                        v.isPressed = false
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_CANCEL -> false
                else -> false
            }
        }
    }
}
