package com.brycewg.asrkb.store

import kotlinx.serialization.Serializable

@Serializable
data class SpeechPreset(
    val id: String,
    val name: String,
    val content: String,
)
