package com.nova.stepdaddylivehd.gateway.ui.player

data class PlayerErrorState(
    val code: String,
    val title: String,
    val detail: String,
    val recoverable: Boolean = true,
)
