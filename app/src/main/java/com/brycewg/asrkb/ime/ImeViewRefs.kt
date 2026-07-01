/**
 * IME View 引用集合，集中绑定键盘各面板控件。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.widgets.PunctKeyView
import com.brycewg.asrkb.ui.widgets.WaveformView

internal class ImeViewRefs private constructor(
    val rootView: View,
    val keyboardFloatingPanel: View?,
    val keyboardDragHandleRow: View?,
    val keyboardDragHandle: View?,

    // Panels
    val layoutMainKeyboard: View?,
    val layoutAiEditPanel: View?,
    val layoutNumpadPanel: View?,
    val layoutClipboardPanel: View?,

    // Main controls
    val btnMic: ImageButton?,
    val btnSettings: ImageButton?,
    val btnEnter: ImageButton?,
    val btnPostproc: ImageButton?,
    val btnAiEdit: ImageButton?,
    val btnBackspace: ImageButton?,
    val btnPromptPicker: ImageButton?,
    val btnHide: ImageButton?,
    val btnImeSwitcher: ImageButton?,

    // Punctuation
    val btnPunct1: ImageButton?,
    val btnPunct2: PunctKeyView?,
    val btnPunct3: PunctKeyView?,
    val btnPunct4: ImageButton?,

    val rowRecordingGestures: View?,

    // Recording gesture overlay
    val btnGestureCancel: TextView?,
    val btnGestureSend: TextView?,

    // Extension buttons
    val btnExt1: ImageButton?,
    val btnExt2: ImageButton?,
    val btnExt3: ImageButton?,
    val btnExt4: ImageButton?,
    val btnExtCenter1: View?,
    val btnExtCenter2: Button?,

    // Status
    val txtStatusText: TextView?,
    val waveformView: WaveformView?,
    val txtStatus: TextView?,
    val groupMicStatus: View?,

    // AI edit panel
    val aiEditInfoBar: View?,
    val txtAiEditInfo: TextView?,
    val btnAiEditPanelBack: ImageButton?,
    val btnAiPanelMic: ImageButton?,
    val btnAiPanelApplyPreset: ImageButton?,
    val btnAiPanelSpace: Button?,
    val btnAiPanelCursorLeft: ImageButton?,
    val btnAiPanelCursorRight: ImageButton?,
    val btnAiPanelMoveStart: ImageButton?,
    val btnAiPanelMoveEnd: ImageButton?,
    val btnAiPanelSelect: ImageButton?,
    val btnAiPanelSelectAll: ImageButton?,
    val btnAiPanelCopy: ImageButton?,
    val btnAiPanelPaste: ImageButton?,
    val btnAiPanelUndo: ImageButton?,
    val btnAiPanelNumpad: ImageButton?,

    // Numpad panel
    val btnNumpadBack: ImageButton?,
    val btnNumpadEnter: ImageButton?,
    val btnNumpadBackspace: ImageButton?,
    val btnNumpadPunctToggle: ImageButton?,

    // Clipboard panel
    val clipBtnBack: ImageButton?,
    val clipBtnDelete: ImageButton?,
    val clipTxtCount: TextView?,
    val clipList: RecyclerView?
) {
    companion object {
        fun bind(rootView: View): ImeViewRefs = ImeViewRefs(
            rootView = rootView,
            keyboardFloatingPanel = rootView.findViewById(R.id.keyboardFloatingPanel),
            keyboardDragHandleRow = rootView.findViewById(R.id.keyboardDragHandleRow),
            keyboardDragHandle = rootView.findViewById(R.id.keyboardDragHandle),

            layoutMainKeyboard = rootView.findViewById(R.id.layoutMainKeyboard),
            layoutAiEditPanel = rootView.findViewById(R.id.layoutAiEditPanel),
            layoutNumpadPanel = rootView.findViewById(R.id.layoutNumpadPanel),
            layoutClipboardPanel = rootView.findViewById(R.id.layoutClipboardPanel),

            btnMic = rootView.findViewById(R.id.btnMic),
            btnSettings = rootView.findViewById(R.id.btnSettings),
            btnEnter = rootView.findViewById(R.id.btnEnter),
            btnPostproc = rootView.findViewById(R.id.btnPostproc),
            btnAiEdit = rootView.findViewById(R.id.btnAiEdit),
            btnBackspace = rootView.findViewById(R.id.btnBackspace),
            btnPromptPicker = rootView.findViewById(R.id.btnPromptPicker),
            btnHide = rootView.findViewById(R.id.btnHide),
            btnImeSwitcher = rootView.findViewById(R.id.btnImeSwitcher),

            btnPunct1 = rootView.findViewById(R.id.btnPunct1),
            btnPunct2 = rootView.findViewById(R.id.btnPunct2),
            btnPunct3 = rootView.findViewById(R.id.btnPunct3),
            btnPunct4 = rootView.findViewById(R.id.btnPunct4),

            rowRecordingGestures = rootView.findViewById(R.id.rowRecordingGestures),

            btnGestureCancel = rootView.findViewById(R.id.btnGestureCancel),
            btnGestureSend = rootView.findViewById(R.id.btnGestureSend),

            btnExt1 = rootView.findViewById(R.id.btnExt1),
            btnExt2 = rootView.findViewById(R.id.btnExt2),
            btnExt3 = rootView.findViewById(R.id.btnExt3),
            btnExt4 = rootView.findViewById(R.id.btnExt4),
            btnExtCenter1 = rootView.findViewById(R.id.btnExtCenter1),
            btnExtCenter2 = rootView.findViewById(R.id.btnExtCenter2),

            txtStatusText = rootView.findViewById(R.id.txtStatusText),
            waveformView = rootView.findViewById(R.id.waveformView),
            txtStatus = rootView.findViewById(R.id.txtStatus),
            groupMicStatus = rootView.findViewById(R.id.groupMicStatus),

            aiEditInfoBar = rootView.findViewById(R.id.aiEditInfoBar),
            txtAiEditInfo = rootView.findViewById(R.id.txtAiEditInfo),

            btnAiEditPanelBack = rootView.findViewById(R.id.btnAiPanelBack),
            btnAiPanelMic = rootView.findViewById(R.id.btnAiPanelMic),
            btnAiPanelApplyPreset = rootView.findViewById(R.id.btnAiPanelApplyPreset),
            btnAiPanelSpace = rootView.findViewById(R.id.btnAiPanelSpace),
            btnAiPanelCursorLeft = rootView.findViewById(R.id.btnAiPanelCursorLeft),
            btnAiPanelCursorRight = rootView.findViewById(R.id.btnAiPanelCursorRight),
            btnAiPanelMoveStart = rootView.findViewById(R.id.btnAiPanelMoveStart),
            btnAiPanelMoveEnd = rootView.findViewById(R.id.btnAiPanelMoveEnd),
            btnAiPanelSelect = rootView.findViewById(R.id.btnAiPanelSelect),
            btnAiPanelSelectAll = rootView.findViewById(R.id.btnAiPanelSelectAll),
            btnAiPanelCopy = rootView.findViewById(R.id.btnAiPanelCopy),
            btnAiPanelPaste = rootView.findViewById(R.id.btnAiPanelPaste),
            btnAiPanelUndo = rootView.findViewById(R.id.btnAiPanelUndo),
            btnAiPanelNumpad = rootView.findViewById(R.id.btnAiPanelNumpad),

            btnNumpadBack = rootView.findViewById(R.id.np_btnBack),
            btnNumpadEnter = rootView.findViewById(R.id.np_btnEnter),
            btnNumpadBackspace = rootView.findViewById(R.id.np_btnBackspace),
            btnNumpadPunctToggle = rootView.findViewById(R.id.np_btnPunctToggle),

            clipBtnBack = rootView.findViewById(R.id.clip_btnBack),
            clipBtnDelete = rootView.findViewById(R.id.clip_btnDelete),
            clipTxtCount = rootView.findViewById(R.id.clip_txtCount),
            clipList = rootView.findViewById(R.id.clip_list)
        )
    }
}
