/**
 * 设置搜索页面。
 *
 * 归属模块：ui/settings/search
 */
package com.brycewg.asrkb.ui.settings.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.WindowInsetsHelper
import com.brycewg.asrkb.util.HapticFeedbackHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import kotlin.math.abs

class SettingsSearchActivity : BaseActivity() {

    private data class Row(
        val entry: SettingsSearchEntry,
        val titleNormalized: String,
        val searchNormalized: String,
    )

    private lateinit var prefs: Prefs
    private lateinit var etSearch: TextInputEditText
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private val adapter = ResultAdapter(
        onClick = { v, entry ->
            hapticTapIfEnabled(v)
            SettingsSearchNavigator.launch(
                source = this,
                activityClass = entry.activityClass,
                targetViewId = entry.targetViewId,
                highlight = true,
                forceAsrVendorId = entry.forceAsrVendorId,
                forceLlmVendorId = entry.forceLlmVendorId,
            )
            finish()
        },
        screenTitleProvider = { getString(it) },
    )

    private var allRows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_search)

        findViewById<View>(android.R.id.content).let { rootView ->
            WindowInsetsHelper.applySystemBarsInsets(rootView, applyBottom = false)
        }

        prefs = Prefs(this)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(R.string.title_settings_search)
            setNavigationOnClickListener { finish() }
        }

        etSearch = findViewById(R.id.etSettingsSearch)
        rv = findViewById(R.id.rvSettingsSearchResults)
        tvEmpty = findViewById(R.id.tvSettingsSearchEmpty)
        rv.layoutManager = LinearLayoutManager(this)
        rv.clipToPadding = false
        rv.adapter = adapter
        WindowInsetsHelper.applyBottomInsets(rv)
        WindowInsetsHelper.applyBottomInsets(tvEmpty)

        etSearch.requestFocus()
        etSearch.post {
            val imm = getSystemService(InputMethodManager::class.java) ?: return@post
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        buildIndex()
        wireSearchBox()
        render(query = "")
    }

    private fun buildIndex() {
        val entries = SettingsSearchIndex.get(this)
        allRows = entries.map { e ->
            val screenTitle = runCatching { getString(e.screenTitleResId) }.getOrNull().orEmpty()
            val sectionPath = e.sectionPath.filter { it.isNotBlank() }
            val searchText = buildString {
                append(e.title)
                for (p in sectionPath) {
                    append(' ')
                    append(p)
                }
                if (screenTitle.isNotBlank()) {
                    append(' ')
                    append(screenTitle)
                }
                for (k in e.keywords) {
                    if (k.isNotBlank()) {
                        append(' ')
                        append(k)
                    }
                }
            }
            Row(
                entry = e,
                titleNormalized = normalizeForSearch(e.title),
                searchNormalized = normalizeForSearch(searchText),
            )
        }
    }

    private fun wireSearchBox() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                render(query = s?.toString().orEmpty())
            }
        })
    }

    private fun render(query: String) {
        val raw = query.trim()
        val q = QueryParts.from(raw, ::normalizeForSearch)
        val filtered = if (q.normalizedAll.isBlank()) {
            allRows.map { it.entry }
        } else {
            allRows
                .asSequence()
                .mapNotNull { row ->
                    val score = matchScore(row, q) ?: return@mapNotNull null
                    row.entry to score
                }
                .sortedWith(
                    compareBy<Pair<SettingsSearchEntry, Int>>(
                        { it.second },
                        { it.first.sectionPath.size },
                        { it.first.title },
                    ),
                )
                .map { it.first }
                .toList()
        }

        adapter.submitList(filtered)
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun normalizeForSearch(raw: String): String {
        return raw
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
    }

    private data class QueryParts(
        val normalizedAll: String,
        val normalizedTerms: List<String>,
    ) {
        companion object {
            fun from(raw: String, normalizer: (String) -> String): QueryParts {
                val terms = raw
                    .split(Regex("\\s+"))
                    .asSequence()
                    .map { normalizer(it) }
                    .filter { it.isNotBlank() }
                    .toList()
                val all = normalizer(raw)
                return QueryParts(normalizedAll = all, normalizedTerms = terms.ifEmpty { listOf(all) }.filter { it.isNotBlank() })
            }
        }
    }

    private fun matchScore(row: Row, query: QueryParts): Int? {
        if (query.normalizedAll.isBlank()) return 0

        var score = 0
        for (term in query.normalizedTerms) {
            val s = matchTermScore(row, term) ?: return null
            score += s
        }

        if (row.titleNormalized == query.normalizedAll) {
            score -= 6
        } else if (row.titleNormalized.startsWith(query.normalizedAll)) {
            score -= 4
        } else if (row.titleNormalized.contains(query.normalizedAll)) {
            score -= 2
        }

        return score.coerceAtLeast(0)
    }

    private fun matchTermScore(row: Row, term: String): Int? {
        if (term.isBlank()) return 0

        val title = row.titleNormalized
        val all = row.searchNormalized

        if (title == term) return 0
        if (title.startsWith(term)) return 1
        if (title.contains(term)) return 2
        if (all.contains(term)) return 6

        if (term.length >= 2) {
            if (containsEditDistanceAtMostOne(title, term)) return 7
            if (containsEditDistanceAtMostOne(all, term)) return 11
        }

        if (term.length >= 3 && isSubsequence(term, title)) {
            return 14 + (title.length - term.length).coerceAtLeast(0)
        }
        if (term.length >= 3 && isSubsequence(term, all)) {
            return 18 + (all.length - term.length).coerceAtLeast(0)
        }
        return null
    }

    private fun containsEditDistanceAtMostOne(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return true

        val n = needle.length
        val lengths = intArrayOf(n - 1, n, n + 1)
        for (len in lengths) {
            if (len <= 0 || len > haystack.length) continue
            for (start in 0..(haystack.length - len)) {
                if (isEditDistanceAtMostOne(needle, haystack, start, len)) return true
            }
        }
        return false
    }

    private fun isEditDistanceAtMostOne(needle: String, haystack: String, start: Int, len: Int): Boolean {
        if (abs(needle.length - len) > 1) return false
        if (needle.length == len) {
            var diff = 0
            for (i in 0 until len) {
                if (needle[i] != haystack[start + i]) {
                    diff++
                    if (diff > 1) return false
                }
            }
            return true
        }

        if (needle.length + 1 == len) {
            var i = 0
            var j = 0
            var edits = 0
            while (i < needle.length && j < len) {
                val cNeedle = needle[i]
                val cHay = haystack[start + j]
                if (cNeedle == cHay) {
                    i++
                    j++
                    continue
                }
                edits++
                if (edits > 1) return false
                j++
            }
            return true
        }

        if (needle.length - 1 == len) {
            var i = 0
            var j = 0
            var edits = 0
            while (i < needle.length && j < len) {
                val cNeedle = needle[i]
                val cHay = haystack[start + j]
                if (cNeedle == cHay) {
                    i++
                    j++
                    continue
                }
                edits++
                if (edits > 1) return false
                i++
            }
            return true
        }

        return false
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        if (needle.isEmpty()) return true
        var i = 0
        for (c in haystack) {
            if (i < needle.length && needle[i] == c) {
                i++
                if (i == needle.length) return true
            }
        }
        return false
    }

    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(this, prefs, view)
    }

    private class ResultAdapter(
        private val onClick: (View, SettingsSearchEntry) -> Unit,
        private val screenTitleProvider: (Int) -> String,
    ) : ListAdapter<SettingsSearchEntry, ResultAdapter.VH>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_search_result, parent, false)
            return VH(v, onClick, screenTitleProvider)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        class VH(
            itemView: View,
            private val onClick: (View, SettingsSearchEntry) -> Unit,
            private val screenTitleProvider: (Int) -> String,
        ) : RecyclerView.ViewHolder(itemView) {
            private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            private val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)

            fun bind(entry: SettingsSearchEntry) {
                tvTitle.text = entry.title
                val screenTitle = screenTitleProvider(entry.screenTitleResId)
                tvSubtitle.text = if (entry.sectionPath.isEmpty()) {
                    screenTitle
                } else {
                    buildString {
                        append(screenTitle)
                        for (p in entry.sectionPath) {
                            if (p.isBlank()) continue
                            append(" → ")
                            append(p)
                        }
                    }
                }
                itemView.setOnClickListener { v -> onClick(v, entry) }
            }
        }

        private companion object {
            val Diff = object : DiffUtil.ItemCallback<SettingsSearchEntry>() {
                override fun areItemsTheSame(oldItem: SettingsSearchEntry, newItem: SettingsSearchEntry): Boolean {
                    return oldItem.activityClass == newItem.activityClass &&
                        oldItem.targetViewId == newItem.targetViewId &&
                        oldItem.title == newItem.title
                }

                override fun areContentsTheSame(oldItem: SettingsSearchEntry, newItem: SettingsSearchEntry): Boolean {
                    return oldItem == newItem
                }
            }
        }
    }
}
