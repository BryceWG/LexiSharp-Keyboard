package com.brycewg.asrkb.ui.settings.compose.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrVolcengineConfigTest {
    @Test
    fun primaryItemCountUsesOneCredentialFieldForNewAuth() {
        assertEquals(
            8,
            volcenginePrimaryItemCount(
                streaming = true,
                useNewAuth = false
            )
        )
        assertEquals(
            7,
            volcenginePrimaryItemCount(
                streaming = true,
                useNewAuth = true
            )
        )
    }
}
