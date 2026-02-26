/**
 * 新手引导页的分页适配器。
 *
 * 归属模块：ui/setup
 */
package com.brycewg.asrkb.ui.setup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R

/**
 * 承载四个固定页面：权限、ASR 选择、隐私、相关信息。
 */
internal class OnboardingPagerAdapter(
    private val callbacks: Callbacks,
) : RecyclerView.Adapter<OnboardingPagerAdapter.PageViewHolder>() {

    interface Callbacks {
        fun bindPermissionPage(root: View)
        fun bindAsrChoicePage(root: View)
        fun bindPrivacyPage(root: View)
        fun bindLinksPage(root: View)
    }

    companion object {
        const val PAGE_PERMISSIONS = 0
        const val PAGE_ASR_CHOICE = 1
        const val PAGE_PRIVACY = 2
        const val PAGE_LINKS = 3
        private const val PAGE_COUNT = 4
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layoutId = when (viewType) {
            PAGE_PERMISSIONS -> R.layout.item_onboarding_page_permissions
            PAGE_ASR_CHOICE -> R.layout.item_onboarding_page_asr_choice
            PAGE_PRIVACY -> R.layout.item_onboarding_page_privacy
            PAGE_LINKS -> R.layout.item_onboarding_page_links
            else -> error("Unsupported onboarding page type: $viewType")
        }
        val view = inflater.inflate(layoutId, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        when (position) {
            PAGE_PERMISSIONS -> callbacks.bindPermissionPage(holder.itemView)
            PAGE_ASR_CHOICE -> callbacks.bindAsrChoicePage(holder.itemView)
            PAGE_PRIVACY -> callbacks.bindPrivacyPage(holder.itemView)
            PAGE_LINKS -> callbacks.bindLinksPage(holder.itemView)
            else -> Unit
        }
    }

    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
