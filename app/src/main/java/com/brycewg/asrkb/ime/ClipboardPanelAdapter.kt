/**
 * IME 剪贴板面板列表适配器。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.clipboard.ClipboardHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BibiViewThemes

class ClipboardPanelAdapter(private val onItemClick: (ClipboardHistoryStore.Entry) -> Unit) : ListAdapter<ClipboardHistoryStore.Entry, ClipboardPanelAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ClipboardHistoryStore.Entry>() {
            override fun areItemsTheSame(
                oldItem: ClipboardHistoryStore.Entry,
                newItem: ClipboardHistoryStore.Entry
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: ClipboardHistoryStore.Entry,
                newItem: ClipboardHistoryStore.Entry
            ): Boolean = oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(createItemView(parent))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = getItem(position)
        holder.bind(e, onItemClick)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView.findViewById(R.id.tvEntry)
        private val pin: View? = itemView.findViewById(R.id.viewPinned)

        fun bind(e: ClipboardHistoryStore.Entry, onClick: (ClipboardHistoryStore.Entry) -> Unit) {
            // 文本与文件统一使用 Entry 自带的展示文案，文件为「EXT-名称」形式
            tv.text = clipboardTextPreview(e.getDisplayLabel())
            itemView.setOnClickListener { onClick(e) }
            pin?.visibility = if (e.pinned) View.VISIBLE else View.GONE
        }
    }

    private fun createItemView(parent: ViewGroup): View {
        val context = parent.context
        val theme = BibiViewThemes.resolve(context, Prefs(context))
        val item = ConstraintLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }

        val tv = TextView(context).apply {
            id = R.id.tvEntry
            background = BibiViewThemes.roundedRipple(
                context,
                theme.keyBackground,
                theme.ripple,
                theme.rectKeyRadiusDp,
                insetDp = theme.keyInsetDp
            )
            foreground = selectableBorderless(context)
            clipToOutline = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isSingleLine = true
            maxLines = 1
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            setTextColor(theme.panelSummary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        item.addView(tv)

        item.addView(
            View(context).apply {
                id = R.id.viewPinned
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    color = ColorStateList.valueOf(theme.primary)
                }
                visibility = View.GONE
                layoutParams = ConstraintLayout.LayoutParams(dp(context, 6), dp(context, 6)).apply {
                    endToEnd = R.id.tvEntry
                    bottomToBottom = R.id.tvEntry
                    marginEnd = dp(context, 8)
                    bottomMargin = dp(context, 8)
                }
            }
        )
        return item
    }

    private fun selectableBorderless(context: android.content.Context): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return ContextCompat.getDrawable(context, outValue.resourceId)
    }

    private fun dp(context: android.content.Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
