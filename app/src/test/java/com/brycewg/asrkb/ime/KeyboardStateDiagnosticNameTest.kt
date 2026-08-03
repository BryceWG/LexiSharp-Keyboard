package com.brycewg.asrkb.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardStateDiagnosticNameTest {
    @Test
    fun diagnosticNamesAreStableAcrossStateInstances() {
        val states = listOf(
            KeyboardState.Idle to "Idle",
            KeyboardState.Listening() to "Listening",
            KeyboardState.Processing to "Processing",
            KeyboardState.AiProcessing("raw") to "AiProcessing",
            KeyboardState.AiEditListening(false, "target") to "AiEditListening",
            KeyboardState.AiEditProcessing(false, "target", "instruction") to "AiEditProcessing"
        )

        states.forEach { (state, expected) -> assertEquals(expected, state.diagnosticName) }
    }
}
