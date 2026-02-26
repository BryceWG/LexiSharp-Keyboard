package com.brycewg.asrkb.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.brycewg.asrkb.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object DownloadSourceDialog {
    private const val TAG = "DownloadSourceDialog"
    private const val LATENCY_TIMEOUT_MS = 3000

    data class Option(val label: String, val url: String)

    fun show(
        context: Context,
        titleRes: Int,
        options: List<Option>,
        cancelable: Boolean = true,
        showCancelButton: Boolean = true,
        onDismiss: (() -> Unit)? = null,
        onSelect: (Option) -> Unit,
    ): AlertDialog {
        val rows = options.map { option ->
            Row(option, buildAddressDisplay(option.url))
        }

        val adapter = RowAdapter(context, rows)
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_download_source_latency, null, false)
        val listView = dialogView.findViewById<ListView>(R.id.listDownloadSources)
        listView.adapter = adapter

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setView(dialogView)

        if (showCancelButton) {
            builder.setNegativeButton(R.string.btn_cancel, null)
        }

        val dialog = builder.create()
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        listView.setOnItemClickListener { _, _, position, _ ->
            val option = options.getOrNull(position) ?: return@setOnItemClickListener
            onSelect(option)
            dialog.dismiss()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        dialog.setOnDismissListener {
            scope.cancel()
            onDismiss?.invoke()
        }

        startLatencyChecks(scope, rows, adapter)
        dialog.show()
        return dialog
    }

    private enum class LatencyStatus { Pending, Ok, Timeout, Error }

    private data class Row(
        val option: Option,
        val address: String,
        var status: LatencyStatus = LatencyStatus.Pending,
        var latencyMs: Long = 0L,
    )

    private class RowAdapter(
        context: Context,
        items: List<Row>,
    ) : ArrayAdapter<Row>(context, R.layout.item_download_source_latency, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(
                R.layout.item_download_source_latency,
                parent,
                false,
            )
            val holder = view.tag as? ViewHolder ?: ViewHolder(view).also { view.tag = it }
            val item = getItem(position)
            if (item != null) {
                holder.title.text = item.option.label
                holder.address.text = item.address
                holder.latency.text = buildLatencyText(context, item)
            }
            return view
        }

        private class ViewHolder(view: View) {
            val title: TextView = view.findViewById(R.id.tvDownloadSourceTitle)
            val address: TextView = view.findViewById(R.id.tvDownloadSourceAddress)
            val latency: TextView = view.findViewById(R.id.tvDownloadSourceLatency)
        }
    }

    private fun buildLatencyText(context: Context, row: Row): String {
        return when (row.status) {
            LatencyStatus.Pending -> context.getString(R.string.download_source_latency_pending)
            LatencyStatus.Timeout -> context.getString(R.string.download_source_latency_timeout)
            LatencyStatus.Error -> context.getString(R.string.download_source_latency_failed)
            LatencyStatus.Ok -> context.getString(
                R.string.download_source_latency_value,
                row.latencyMs,
            )
        }
    }

    private fun startLatencyChecks(
        scope: CoroutineScope,
        rows: List<Row>,
        adapter: RowAdapter,
    ) {
        rows.forEach { row ->
            scope.launch {
                val result = withContext(Dispatchers.IO) { measureLatency(row.option.url) }
                if (!isActive) return@launch
                row.status = result.status
                row.latencyMs = result.latencyMs
                adapter.notifyDataSetChanged()
            }
        }
    }

    private data class LatencyResult(
        val status: LatencyStatus,
        val latencyMs: Long = 0L,
    )

    private fun measureLatency(url: String): LatencyResult {
        val hostPort = parseHostPort(url)
        if (hostPort == null) {
            Log.w(TAG, "Missing host for latency check: $url")
            return LatencyResult(LatencyStatus.Error)
        }
        val (host, port) = hostPort
        val firstAttempt = connectOnce(host, port)
        if (firstAttempt.status != LatencyStatus.Timeout) {
            return firstAttempt
        }
        Log.w(TAG, "Latency check timeout, retrying: $host:$port")
        return connectOnce(host, port)
    }

    private fun connectOnce(host: String, port: Int): LatencyResult {
        val start = SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                socket.soTimeout = LATENCY_TIMEOUT_MS
                socket.connect(InetSocketAddress(host, port), LATENCY_TIMEOUT_MS)
            }
            val cost = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            LatencyResult(LatencyStatus.Ok, cost)
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Latency check timeout: $host:$port", e)
            LatencyResult(LatencyStatus.Timeout)
        } catch (e: Exception) {
            Log.w(TAG, "Latency check failed: $host:$port", e)
            LatencyResult(LatencyStatus.Error)
        }
    }

    private fun parseHostPort(url: String): Pair<String, Int>? {
        val uri = Uri.parse(url)
        val host = uri.host ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("http", true) -> 80
            else -> 443
        }
        return host to port
    }

    private fun buildAddressDisplay(url: String): String {
        val uri = Uri.parse(url)
        val host = uri.host ?: return url
        val scheme = uri.scheme ?: "https"
        return "$scheme://$host"
    }
}
