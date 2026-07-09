package com.brycewg.asrkb.ui.settings.compose.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrVolcengineConfigTest {
    @Test
    fun primaryItemCountUsesOneCredentialFieldForNewAuth() {
        assertEquals(
            9,
            volcenginePrimaryItemCount(
                streaming = true,
                fileStandard = true,
                useNewAuth = false
            )
        )
        assertEquals(
            8,
            volcenginePrimaryItemCount(
                streaming = true,
                fileStandard = true,
                useNewAuth = true
            )
        )
    }
}
