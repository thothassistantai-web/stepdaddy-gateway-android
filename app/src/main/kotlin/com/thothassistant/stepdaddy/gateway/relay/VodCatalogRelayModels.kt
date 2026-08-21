package com.thothassistant.stepdaddy.gateway.relay

import kotlinx.serialization.Serializable

@Serializable
data class VodCatalogRelayManifest(
    val version: Int,
    val minAppVersion: String? = null,
    val message: String? = null,
    val movies: List<VodRelayMovie> = emptyList(),
    val shows: List<VodRelayShow> = emptyList(),
)

@Serializable
data class VodRelayMovie(
    val tmdbId: Int = 0,
    val title: String = "",
    val year: String? = null,
    val imdbId: String? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val streams: List<VodRelayStream> = emptyList(),
)

@Serializable
data class VodRelayShow(
    val tmdbId: Int = 0,
    val title: String = "",
    val year: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeTitle: String? = null,
    val imdbId: String? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val streams: List<VodRelayStream> = emptyList(),
)

@Serializable
data class VodRelayStream(
    val url: String,
    val quality: String? = null,
    val label: String? = null,
    val referer: String? = null,
)

@Serializable
data class VodCatalogRelayHealth(
    val active: Boolean = false,
    val version: Int = 0,
    val source: String = "",
    val fetchedAtMs: Long = 0L,
    val movies: Int = 0,
    val shows: Int = 0,
    val probed: Int = 0,
    val probeOk: Int = 0,
    val deadPruned: Int = 0,
)

data class VodCatalogRelayStatus(
    val active: Boolean = false,
    val version: Int = 0,
    val source: String = "",
    val fetchedAtMs: Long = 0L,
    val message: String = "",
    val movies: Int = 0,
    val shows: Int = 0,
    val probed: Int = 0,
    val probeOk: Int = 0,
    val deadPruned: Int = 0,
)

data class VodRelayWorkingStream(
    val url: String,
    val referer: String?,
    val quality: String?,
    val label: String?,
)
