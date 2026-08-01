package com.brycewg.asrkb.ime

import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InputConnectionHelperStreamingPreviewTest {
    @Test
    fun committedComposingUpdatesReplaceOwnedPreview() {
        val editor = CommittingInputConnection("prefix:")
        val helper = InputConnectionHelper()

        helper.setStreamingPreview(editor.connection, "raw")
        helper.replaceStreamingPreview(editor.connection, "processed-final")
        helper.finishComposingText(editor.connection)

        assertEquals("prefix:processed-final", editor.text)
    }

    @Test
    fun changedEditorContextIsNotDeletedAsOwnedPreview() {
        val editor = CommittingInputConnection("prefix:")
        val helper = InputConnectionHelper()

        helper.setStreamingPreview(editor.connection, "raw")
        editor.appendUserText(" user")
        helper.replaceStreamingPreview(editor.connection, "processed")

        assertEquals("prefix:raw userprocessed", editor.text)
    }

    @Test
    fun failedFinishDoesNotBlockNextPreview() {
        val editor = CommittingInputConnection("prefix:")
        val helper = InputConnectionHelper()

        helper.setStreamingPreview(editor.connection, "raw")
        editor.finishSucceeds = false
        assertFalse(helper.finishComposingText(editor.connection))
        assertTrue(helper.setStreamingPreview(editor.connection, "next"))

        assertEquals("prefix:rawnext", editor.text)
    }

    @Test
    fun failedReplacementRestoresOwnedPreview() {
        val editor = CommittingInputConnection("prefix:")
        val helper = InputConnectionHelper()

        helper.setStreamingPreview(editor.connection, "raw")
        editor.rejectNextComposingWrite = true
        assertFalse(helper.replaceStreamingPreview(editor.connection, "processed"))

        assertEquals("prefix:raw", editor.text)
    }

    @Test
    fun partialUpdatesUseBoundedAnchorAndOneVerificationRead() {
        val editor = CommittingInputConnection("p".repeat(1_000))
        val helper = InputConnectionHelper()

        helper.setStreamingPreview(editor.connection, "a".repeat(512))
        assertTrue(editor.beforeCursorRequests.first() <= 256)

        editor.beforeCursorRequests.clear()
        helper.setStreamingPreview(editor.connection, "b".repeat(520))
        assertEquals(1, editor.beforeCursorRequests.size)

        helper.replaceStreamingPreview(editor.connection, "final")
        assertEquals("p".repeat(1_000) + "final", editor.text)
    }

    private class CommittingInputConnection(initialText: String) {
        private val content = StringBuilder(initialText)
        var finishSucceeds = true
        var rejectNextComposingWrite = false
        val beforeCursorRequests = mutableListOf<Int>()

        val text: String
            get() = content.toString()

        val connection: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "setComposingText", "commitText" -> {
                    if (rejectNextComposingWrite) {
                        rejectNextComposingWrite = false
                        return@newProxyInstance false
                    }
                    content.append(args?.get(0).toString())
                    true
                }
                "getTextBeforeCursor" -> {
                    val length = args?.get(0) as Int
                    beforeCursorRequests += length
                    content.takeLast(length)
                }
                "deleteSurroundingText" -> {
                    val beforeLength = args?.get(0) as Int
                    content.delete(content.length - beforeLength, content.length)
                    true
                }
                "finishComposingText" -> finishSucceeds
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CommittingInputConnection"
                else -> defaultValue(method.returnType)
            }
        } as InputConnection

        fun appendUserText(text: String) {
            content.append(text)
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }
}
