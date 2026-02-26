package com.brycewg.asrkb.ime

import android.util.Log
import android.view.inputmethod.InputConnection

internal class UndoManager(
    private val inputHelper: InputConnectionHelper,
    private val logTag: String,
    private val maxSnapshots: Int = 3,
) {
    private val snapshots = ArrayDeque<UndoSnapshot>(maxSnapshots)

    fun saveSnapshot(ic: InputConnection, force: Boolean = false) {
        val topSnapshot = snapshots.lastOrNull()

        if (force || topSnapshot == null) {
            val newSnapshot = inputHelper.captureUndoSnapshot(ic)
            if (newSnapshot != null) {
                snapshots.addLast(newSnapshot)
                trim()
            }
            return
        }

        try {
            val currentBefore = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
            val currentAfter = inputHelper.getTextAfterCursor(ic, 10000)?.toString() ?: ""
            val snapshotBefore = topSnapshot.beforeCursor.toString()
            val snapshotAfter = topSnapshot.afterCursor.toString()

            if (currentBefore != snapshotBefore || currentAfter != snapshotAfter) {
                val newSnapshot = inputHelper.captureUndoSnapshot(ic)
                if (newSnapshot != null) {
                    snapshots.addLast(newSnapshot)
                    trim()
                }
            }
        } catch (e: Throwable) {
            Log.w(logTag, "Failed to compare snapshot, saving anyway", e)
            val newSnapshot = inputHelper.captureUndoSnapshot(ic)
            if (newSnapshot != null) {
                snapshots.addLast(newSnapshot)
                trim()
            }
        }
    }

    fun popAndRestoreSnapshot(ic: InputConnection): Int? {
        val snapshot = snapshots.removeLastOrNull() ?: return null
        val ok = inputHelper.restoreSnapshot(ic, snapshot)
        if (!ok) return null
        return snapshots.size
    }

    fun clear() {
        snapshots.clear()
    }

    private fun trim() {
        while (snapshots.size > maxSnapshots) {
            snapshots.removeFirst()
        }
    }
}
