package com.thothassistant.stepdaddy.gateway.ui.player

data class PlayerErrorState(
    val code: String,
    val title: String,
    val detail: String,
    val recoverable: Boolean = true,
)
