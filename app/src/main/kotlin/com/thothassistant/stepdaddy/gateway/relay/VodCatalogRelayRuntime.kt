package com.thothassistant.stepdaddy.gateway.relay

/**
 * Process-wide VOD overlay: catalog titles + working stream candidates after probe.
 */
object VodCatalogRelayRuntime {
    @Volatile
    private var state: AppliedState? = null

    val isApplied: Boolean
        get() = state != null

    val version: Int
        get() = state?.manifest?.version ?: 0

    val sourceLabel: String
        get() = state?.sourceLabel.orEmpty()

    val fetchedAtMs: Long
        get() = state?.fetchedAtMs ?: 0L

    val message: String
        get() = state?.manifest?.message.orEmpty()

    fun apply(
        manifest: VodCatalogRelayManifest,
        sourceLabel: String,
        fetchedAtMs: Long,
        movieStreams: Map<Int, List<VodRelayWorkingStream>>,
        showStreams: Map<String, List<VodRelayWorkingStream>>,
        probed: Int,
        probeOk: Int,
        deadPruned: Int,
    ) {
        state = AppliedState(
            manifest = manifest,
            sourceLabel = sourceLabel,
            fetchedAtMs = fetchedAtMs,
            movieStreams = movieStreams,
            showStreams = showStreams,
            probed = probed,
            probeOk = probeOk,
            deadPruned = deadPruned,
        )
    }

    fun clear() {
        state = null
    }

    fun overlayMovies(): List<VodRelayMovie> = state?.manifest?.movies.orEmpty()

    fun overlayShows(): List<VodRelayShow> = state?.manifest?.shows.orEmpty()

    fun workingStreamsForMovie(tmdbId: Int): List<VodRelayWorkingStream> =
        state?.movieStreams?.get(tmdbId).orEmpty()

    fun workingStreamsForEpisode(showTmdbId: Int, season: Int, episode: Int): List<VodRelayWorkingStream> =
        state?.showStreams?.get("$showTmdbId:$season:$episode").orEmpty()

    fun status(): VodCatalogRelayStatus {
        val current = state ?: return VodCatalogRelayStatus()
        val movieCount = current.manifest.movies.size
        val showCount = current.manifest.shows.size
        val hasWorking = current.movieStreams.isNotEmpty() || current.showStreams.isNotEmpty()
        val active = movieCount > 0 || showCount > 0 || hasWorking
        return VodCatalogRelayStatus(
            active = active,
            version = current.manifest.version,
            source = current.sourceLabel,
            fetchedAtMs = current.fetchedAtMs,
            message = current.manifest.message.orEmpty(),
            movies = movieCount,
            shows = showCount,
            probed = current.probed,
            probeOk = current.probeOk,
            deadPruned = current.deadPruned,
        )
    }

    fun healthSnapshot(): VodCatalogRelayHealth {
        val s = status()
        return VodCatalogRelayHealth(
            active = s.active,
            version = s.version,
            source = s.source,
            fetchedAtMs = s.fetchedAtMs,
            movies = s.movies,
            shows = s.shows,
            probed = s.probed,
            probeOk = s.probeOk,
            deadPruned = s.deadPruned,
        )
    }

    private data class AppliedState(
        val manifest: VodCatalogRelayManifest,
        val sourceLabel: String,
        val fetchedAtMs: Long,
        val movieStreams: Map<Int, List<VodRelayWorkingStream>>,
        val showStreams: Map<String, List<VodRelayWorkingStream>>,
        val probed: Int,
        val probeOk: Int,
        val deadPruned: Int,
    )
}
