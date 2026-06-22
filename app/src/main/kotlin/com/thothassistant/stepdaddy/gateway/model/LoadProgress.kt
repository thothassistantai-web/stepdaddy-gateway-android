package com.thothassistant.stepdaddy.gateway.model

import kotlinx.serialization.Serializable

/** Dashboard / health loading state for a single stat tile. */
@Serializable
data class LoadProgress(
    /** ready | loading | building | error | offline | idle */
    val phase: String = "idle",
    val percent: Int = 0,
    val etaSeconds: Long? = null,
    val detail: String? = null,
)

@Serializable
data class DashboardLoadProgress(
    val channels: LoadProgress = LoadProgress(),
    val programs: LoadProgress = LoadProgress(),
    val sources: LoadProgress = LoadProgress(),
    val status: LoadProgress = LoadProgress(),
)
