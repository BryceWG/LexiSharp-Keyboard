package com.brycewg.asrkb.store

import kotlinx.serialization.Serializable

@Serializable
data class PromptPreset(
    val id: String,
    val title: String,
    val content: String,
)
