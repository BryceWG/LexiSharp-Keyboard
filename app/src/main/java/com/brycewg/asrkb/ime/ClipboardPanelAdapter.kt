/**
 * IME 剪贴板面板列表适配器。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.clipboard.ClipboardHistoryStore
import com.brycewg.asrkb.clipboard.EntryType
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
        holder.bind(getItem(position), onItemClick)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView.findViewById(R.id.tvEntry)
        private val icon: ImageView = itemView.findViewById(R.id.clip_itemIcon)

        fun bind(e: ClipboardHistoryStore.Entry, onClick: (ClipboardHistoryStore.Entry) -> Unit) {
            tv.text = clipboardTextPreview(e.getDisplayLabel())
            val isFile = e.type != EntryType.TEXT
            icon.visibility = if (isFile) View.VISIBLE else View.GONE
            if (isFile) {
                icon.setImageResource(R.drawable.article_fill)
            }
            itemView.setOnClickListener { onClick(e) }
        }
    }

    private fun createItemView(parent: ViewGroup): View {
        val context = parent.context
        val theme = BibiViewThemes.resolve(context, Prefs(context))
        val radiusDp = ClipboardPanelMetrics.itemRadiusDp(theme.panelRadiusDp)
        val iconSize = dp(context, 18)

        val item = ConstraintLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            background = BibiViewThemes.roundedRipple(
                context,
                theme.keyBackground,
                theme.ripple,
                radiusDp,
                insetDp = 0
            )
            clipToOutline = true
            isClickable = true
            isFocusable = true
            minHeight = dp(context, 44)
            setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11))
        }

        val icon = ImageView(context).apply {
            id = R.id.clip_itemIcon
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            imageTintList = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(theme.keyContent, (0.72f * 255).toInt())
            )
            layoutParams = ConstraintLayout.LayoutParams(iconSize, iconSize).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        item.addView(icon)

        val tv = TextView(context).apply {
            id = R.id.tvEntry
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            maxLines = 2
            setTextColor(theme.keyContent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                startToEnd = R.id.clip_itemIcon
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                marginStart = dp(context, 8)
                goneStartMargin = 0
                matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
            }
        }
        item.addView(tv)
        return item
    }

    private fun dp(context: android.content.Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
