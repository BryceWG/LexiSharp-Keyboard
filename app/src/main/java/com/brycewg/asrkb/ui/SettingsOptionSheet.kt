/**
 * 设置页通用选项面板（BottomSheetDialog），用于展示单选/多选等菜单。
 */
package com.brycewg.asrkb.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.HapticFeedbackHelper
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import kotlin.math.roundToInt

object SettingsOptionSheet {
    data class Tag(
        val label: String,
        val bgColorResId: Int,
        val textColorResId: Int,
    )

    data class TaggedItem(
        val title: String,
        val tags: List<Tag>,
    )

    data class TaggedIndexedItem(
        val originalIndex: Int,
        val item: TaggedItem,
    )

    data class TaggedGroup(
        val label: String,
        val items: List<TaggedIndexedItem>,
    )

    fun showSingleChoice(
        context: Context,
        @StringRes titleResId: Int,
        items: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        if (items.isEmpty()) {
            return
        }

        val dialog = BottomSheetDialog(context, R.style.SettingsBottomSheetDialog)
        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.bottom_sheet_single_choice, null, false)
        val titleView = contentView.findViewById<TextView>(R.id.tvBottomSheetTitle)
        val listView = contentView.findViewById<ListView>(R.id.listBottomSheetOptions)
        val prefs = Prefs(context)
        var selectionHandled = false

        titleView.setText(titleResId)
        val adapter = ArrayAdapter(context, R.layout.item_settings_bottom_sheet_single_choice, items)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE
        capListHeightToMaxSheetHeight(contentView, listView)

        val safeIndex = selectedIndex.takeIf { it in items.indices } ?: -1
        if (safeIndex >= 0) {
            listView.setItemChecked(safeIndex, true)
            listView.setSelection(safeIndex)
        }

        listView.setOnItemClickListener { _, view, position, _ ->
            if (selectionHandled) return@setOnItemClickListener
            selectionHandled = true
            HapticFeedbackHelper.performTap(context, prefs, view)
            dialog.dismiss()
            context.mainExecutor.execute { onSelected(position) }
        }

        dialog.setContentView(contentView)
        dialog.setOnShowListener {
            listView.post { adjustBottomSheetHeight(dialog, contentView, listView) }
        }
        dialog.show()
    }

    fun showSingleChoiceTagged(
        context: Context,
        @StringRes titleResId: Int,
        items: List<TaggedItem>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        if (items.isEmpty()) {
            return
        }

        val dialog = BottomSheetDialog(context, R.style.SettingsBottomSheetDialog)
        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.bottom_sheet_single_choice, null, false)
        val titleView = contentView.findViewById<TextView>(R.id.tvBottomSheetTitle)
        val listView = contentView.findViewById<ListView>(R.id.listBottomSheetOptions)
        val prefs = Prefs(context)
        var selectionHandled = false

        titleView.setText(titleResId)
        val adapter = TaggedChoiceAdapter(context, items, selectedIndex)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE
        capListHeightToMaxSheetHeight(contentView, listView)

        val safeIndex = selectedIndex.takeIf { it in items.indices } ?: -1
        if (safeIndex >= 0) {
            listView.setItemChecked(safeIndex, true)
            listView.setSelection(safeIndex)
        }

        listView.setOnItemClickListener { _, view, position, _ ->
            if (selectionHandled) return@setOnItemClickListener
            selectionHandled = true
            HapticFeedbackHelper.performTap(context, prefs, view)
            dialog.dismiss()
            context.mainExecutor.execute { onSelected(position) }
        }

        dialog.setContentView(contentView)
        dialog.setOnShowListener {
            listView.post { adjustBottomSheetHeight(dialog, contentView, listView) }
        }
        dialog.show()
    }

    fun showSingleChoiceTaggedGrouped(
        context: Context,
        @StringRes titleResId: Int,
        groups: List<TaggedGroup>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        val visibleGroups = groups.filter { it.items.isNotEmpty() }
        if (visibleGroups.isEmpty()) {
            return
        }

        val dialog = BottomSheetDialog(context, R.style.SettingsBottomSheetDialog)
        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.bottom_sheet_single_choice, null, false)
        val titleView = contentView.findViewById<TextView>(R.id.tvBottomSheetTitle)
        val listView = contentView.findViewById<ListView>(R.id.listBottomSheetOptions)
        val prefs = Prefs(context)
        var selectionHandled = false

        titleView.setText(titleResId)
        val adapter = GroupedTaggedChoiceAdapter(context, visibleGroups, selectedIndex)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE
        capListHeightToMaxSheetHeight(contentView, listView)

        val selectedPosition = adapter.positionOfOriginalIndex(selectedIndex)
        if (selectedPosition >= 0) {
            listView.setItemChecked(selectedPosition, true)
            listView.setSelection(selectedPosition)
        }

        listView.setOnItemClickListener { _, view, position, _ ->
            if (selectionHandled) return@setOnItemClickListener
            val originalIndex = adapter.getOriginalIndex(position) ?: return@setOnItemClickListener
            selectionHandled = true
            HapticFeedbackHelper.performTap(context, prefs, view)
            dialog.dismiss()
            context.mainExecutor.execute { onSelected(originalIndex) }
        }

        dialog.setContentView(contentView)
        dialog.setOnShowListener {
            listView.post { adjustBottomSheetHeight(dialog, contentView, listView) }
        }
        dialog.show()
    }

    fun showMultiChoice(
        context: Context,
        @StringRes titleResId: Int,
        items: List<String>,
        initialCheckedItems: BooleanArray,
        onCheckedChanged: (
            (
                listView: ListView,
                which: Int,
                isChecked: Boolean,
                checkedItems: BooleanArray,
            ) -> Unit
        )? = null,
    ) {
        if (items.isEmpty()) {
            return
        }

        val dialog = BottomSheetDialog(context, R.style.SettingsBottomSheetDialog)
        val contentView = LayoutInflater.from(context)
            .inflate(R.layout.bottom_sheet_single_choice, null, false)
        val titleView = contentView.findViewById<TextView>(R.id.tvBottomSheetTitle)
        val listView = contentView.findViewById<ListView>(R.id.listBottomSheetOptions)
        val prefs = Prefs(context)

        val checkedItems = BooleanArray(items.size) { idx ->
            initialCheckedItems.getOrNull(idx) == true
        }

        titleView.setText(titleResId)
        val adapter = ArrayAdapter(context, R.layout.item_settings_bottom_sheet_multi_choice, items)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        capListHeightToMaxSheetHeight(contentView, listView)

        for (index in checkedItems.indices) {
            listView.setItemChecked(index, checkedItems[index])
        }

        checkedItems.indexOfFirst { it }.takeIf { it >= 0 }?.let { firstChecked ->
            listView.setSelection(firstChecked)
        }

        listView.setOnItemClickListener { _, view, position, _ ->
            HapticFeedbackHelper.performTap(context, prefs, view)
            val isChecked = listView.isItemChecked(position)
            checkedItems[position] = isChecked
            onCheckedChanged?.invoke(listView, position, isChecked, checkedItems)
        }

        dialog.setContentView(contentView)
        dialog.setOnShowListener {
            listView.post { adjustBottomSheetHeight(dialog, contentView, listView) }
        }
        dialog.show()
    }

    private class TaggedChoiceAdapter(
        context: Context,
        private val items: List<TaggedItem>,
        private val selectedIndex: Int,
    ) : ArrayAdapter<TaggedItem>(context, 0, items) {
        private val inflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView
                ?: inflater.inflate(R.layout.item_settings_bottom_sheet_single_choice_tagged, parent, false)
            val titleView = row.findViewById<CheckedTextView>(android.R.id.text1)
            val tagGroup = row.findViewById<ChipGroup>(R.id.cgOptionTags)
            val item = items[position]
            val listView = parent as? ListView
            row.setOnClickListener {
                listView?.performItemClick(row, position, getItemId(position))
            }

            titleView.text = item.title
            titleView.isChecked = position == selectedIndex
            tagGroup.isClickable = false
            tagGroup.isFocusable = false
            tagGroup.isFocusableInTouchMode = false
            tagGroup.isLongClickable = false

            val tags = item.tags.filter { it.label.isNotBlank() }
            if (tags.isEmpty()) {
                tagGroup.visibility = View.GONE
                if (tagGroup.childCount > 0) {
                    tagGroup.removeAllViews()
                }
            } else {
                tagGroup.visibility = View.VISIBLE
                while (tagGroup.childCount > tags.size) {
                    tagGroup.removeViewAt(tagGroup.childCount - 1)
                }
                while (tagGroup.childCount < tags.size) {
                    val tagView =
                        inflater.inflate(R.layout.item_settings_tag_chip, tagGroup, false) as TextView
                    tagGroup.addView(tagView)
                }
                for (index in tags.indices) {
                    val tag = tags[index]
                    val tagView = tagGroup.getChildAt(index) as TextView
                    tagView.text = tag.label
                    applyTagColors(tagView, tag)
                }
            }
            return row
        }

        private fun applyTagColors(tagView: TextView, tag: Tag) {
            val bg = ContextCompat.getColor(tagView.context, tag.bgColorResId)
            val fg = ContextCompat.getColor(tagView.context, tag.textColorResId)
            tagView.backgroundTintList = ColorStateList.valueOf(bg)
            tagView.setTextColor(fg)
        }
    }

    private sealed interface GroupedTaggedRow {
        data class Divider(val label: String) : GroupedTaggedRow
        data class Option(val originalIndex: Int, val item: TaggedItem) : GroupedTaggedRow
    }

    private class GroupedTaggedChoiceAdapter(
        context: Context,
        groups: List<TaggedGroup>,
        private val selectedIndex: Int,
    ) : ArrayAdapter<GroupedTaggedRow>(context, 0) {
        private val inflater = LayoutInflater.from(context)
        private val rows: List<GroupedTaggedRow> = buildRows(groups)

        init {
            addAll(rows)
        }

        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean {
            return getItem(position) is GroupedTaggedRow.Option
        }

        override fun getItemViewType(position: Int): Int {
            return when (getItem(position)) {
                is GroupedTaggedRow.Divider -> VIEW_TYPE_DIVIDER
                is GroupedTaggedRow.Option -> VIEW_TYPE_OPTION
                null -> VIEW_TYPE_OPTION
            }
        }

        override fun getViewTypeCount(): Int = 2

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): GroupedTaggedRow? {
            return rows.getOrNull(position)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = getItem(position)) {
                is GroupedTaggedRow.Divider -> {
                    val view = convertView
                        ?: inflater.inflate(R.layout.item_settings_bottom_sheet_section_divider, parent, false)
                    val title = view.findViewById<TextView>(R.id.tvSectionDividerLabel)
                    title.text = row.label
                    view
                }

                is GroupedTaggedRow.Option -> {
                    val view = convertView
                        ?: inflater.inflate(R.layout.item_settings_bottom_sheet_single_choice_tagged, parent, false)
                    val titleView = view.findViewById<CheckedTextView>(android.R.id.text1)
                    val tagGroup = view.findViewById<ChipGroup>(R.id.cgOptionTags)
                    val listView = parent as? ListView
                    view.setOnClickListener {
                        listView?.performItemClick(view, position, getItemId(position))
                    }

                    titleView.text = row.item.title
                    titleView.isChecked = row.originalIndex == selectedIndex
                    tagGroup.isClickable = false
                    tagGroup.isFocusable = false
                    tagGroup.isFocusableInTouchMode = false
                    tagGroup.isLongClickable = false

                    val tags = row.item.tags.filter { it.label.isNotBlank() }
                    if (tags.isEmpty()) {
                        tagGroup.visibility = View.GONE
                        if (tagGroup.childCount > 0) {
                            tagGroup.removeAllViews()
                        }
                    } else {
                        tagGroup.visibility = View.VISIBLE
                        while (tagGroup.childCount > tags.size) {
                            tagGroup.removeViewAt(tagGroup.childCount - 1)
                        }
                        while (tagGroup.childCount < tags.size) {
                            val tagView =
                                inflater.inflate(R.layout.item_settings_tag_chip, tagGroup, false) as TextView
                            tagGroup.addView(tagView)
                        }
                        for (index in tags.indices) {
                            val tag = tags[index]
                            val tagView = tagGroup.getChildAt(index) as TextView
                            tagView.text = tag.label
                            applyTagColors(tagView, tag)
                        }
                    }
                    view
                }

                null -> {
                    convertView
                        ?: inflater.inflate(R.layout.item_settings_bottom_sheet_single_choice_tagged, parent, false)
                }
            }
        }

        fun getOriginalIndex(position: Int): Int? {
            return (getItem(position) as? GroupedTaggedRow.Option)?.originalIndex
        }

        fun positionOfOriginalIndex(originalIndex: Int): Int {
            return rows.indexOfFirst { row ->
                (row as? GroupedTaggedRow.Option)?.originalIndex == originalIndex
            }
        }

        private fun applyTagColors(tagView: TextView, tag: Tag) {
            val bg = ContextCompat.getColor(tagView.context, tag.bgColorResId)
            val fg = ContextCompat.getColor(tagView.context, tag.textColorResId)
            tagView.backgroundTintList = ColorStateList.valueOf(bg)
            tagView.setTextColor(fg)
        }

        private fun buildRows(groups: List<TaggedGroup>): List<GroupedTaggedRow> {
            val showFirstDivider = groups.size > 1
            return buildList {
                groups.forEachIndexed { groupIndex, group ->
                    if (group.items.isEmpty()) return@forEachIndexed
                    if ((groupIndex == 0 && showFirstDivider) || groupIndex > 0) {
                        if (group.label.isNotBlank()) {
                            add(GroupedTaggedRow.Divider(group.label))
                        }
                    }
                    group.items.forEach { item ->
                        add(GroupedTaggedRow.Option(item.originalIndex, item.item))
                    }
                }
            }
        }

        companion object {
            private const val VIEW_TYPE_DIVIDER = 0
            private const val VIEW_TYPE_OPTION = 1
        }
    }

    private fun adjustBottomSheetHeight(
        dialog: BottomSheetDialog,
        contentView: View,
        listView: ListView,
    ) {
        val bottomSheet =
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return
        val parentHeight = (bottomSheet.parent as? View)?.height ?: 0
        val availableHeight =
            parentHeight.takeIf { it > 0 } ?: contentView.resources.displayMetrics.heightPixels
        val maxHeight = (availableHeight * 0.75f).roundToInt()
        val sheetVerticalPadding = bottomSheet.paddingTop + bottomSheet.paddingBottom

        val headerView = contentView.findViewById<View>(R.id.layoutBottomSheetHeader)
        val sheetWidth = bottomSheet.width
            .takeIf { it > 0 }
            ?: contentView.resources.displayMetrics.widthPixels
        val availableWidth =
            (sheetWidth - bottomSheet.paddingLeft - bottomSheet.paddingRight).coerceAtLeast(0)
        val headerHeight = measureViewHeight(headerView, availableWidth)
        val contentVerticalPadding = contentView.paddingTop + contentView.paddingBottom
        val maxListHeight =
            (maxHeight - sheetVerticalPadding - headerHeight - contentVerticalPadding).coerceAtLeast(0)
        val targetListHeight = computeListHeightForSheet(listView, maxListHeight)

        val listLayoutParams = listView.layoutParams
        if (listLayoutParams.height != targetListHeight) {
            listLayoutParams.height = targetListHeight
            listView.layoutParams = listLayoutParams
        }

        val targetHeight =
            (sheetVerticalPadding + headerHeight + contentVerticalPadding + targetListHeight)
                .coerceAtMost(maxHeight)
        val sheetLayoutParams = bottomSheet.layoutParams
        if (sheetLayoutParams.height != targetHeight) {
            sheetLayoutParams.height = targetHeight
            bottomSheet.layoutParams = sheetLayoutParams
        }
        bottomSheet.requestLayout()

        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.isHideable = true
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        installDragGuard(behavior, listView)
    }

    private fun capListHeightToMaxSheetHeight(contentView: View, listView: ListView) {
        val maxHeight = (contentView.resources.displayMetrics.heightPixels * 0.75f).roundToInt()
        val sheetWidth = contentView.resources.displayMetrics.widthPixels
        val headerView = contentView.findViewById<View>(R.id.layoutBottomSheetHeader)
        val headerHeight = measureViewHeight(headerView, sheetWidth)
        val verticalPadding = contentView.paddingTop + contentView.paddingBottom
        val maxListHeight = (maxHeight - headerHeight - verticalPadding).coerceAtLeast(0)

        val listLayoutParams = listView.layoutParams
        if (listLayoutParams.height != maxListHeight) {
            listLayoutParams.height = maxListHeight
            listView.layoutParams = listLayoutParams
        }
    }

    private fun computeListHeightForSheet(listView: ListView, maxListHeight: Int): Int {
        if (maxListHeight <= 0) {
            return 0
        }

        val adapter = listView.adapter ?: return maxListHeight
        if (adapter.count <= 0) {
            return 0
        }

        val canScrollUp = listView.canScrollVertically(-1)
        val canScrollDown = listView.canScrollVertically(1)
        if (canScrollUp || canScrollDown) {
            return maxListHeight
        }

        if (listView.childCount != adapter.count || listView.childCount <= 0) {
            return maxListHeight
        }

        var totalHeight = listView.paddingTop + listView.paddingBottom
        for (index in 0 until listView.childCount) {
            totalHeight += listView.getChildAt(index).height
        }
        val dividerCount = (adapter.count - 1).coerceAtLeast(0)
        totalHeight += listView.dividerHeight * dividerCount
        return totalHeight.coerceAtMost(maxListHeight)
    }

    private fun installDragGuard(
        behavior: BottomSheetBehavior<out View>,
        listView: ListView,
    ) {
        fun updateDraggable() {
            behavior.isDraggable = !listView.canScrollVertically(-1)
        }

        updateDraggable()
        listView.post { updateDraggable() }

        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                updateDraggable()
            }

            override fun onScroll(
                view: AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                updateDraggable()
            }
        })

        listView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                -> listView.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> listView.parent?.requestDisallowInterceptTouchEvent(false)
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> updateDraggable()
            }
            false
        }
    }

    private fun measureViewHeight(view: View?, maxWidth: Int): Int {
        if (view == null) {
            return 0
        }
        if (view.height > 0) {
            return view.height
        }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        return view.measuredHeight
    }
}
