package com.thothassistant.stepdaddy.gateway.relay

import com.thothassistant.stepdaddy.gateway.update.PatchVersionComparator
import java.net.URI

object VodCatalogRelayValidator {
    const val MAX_BYTES = 64 * 1024

    private val HOST_REGEX = Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$""")

    fun parseAndValidate(
        text: String,
        installedVersionName: String,
        cachedVersion: Int,
        decode: (String) -> VodCatalogRelayManifest,
    ): Result<VodCatalogRelayManifest> {
        if (text.length > MAX_BYTES) {
            return Result.failure(IllegalArgumentException("vod-catalog-relay exceeds ${MAX_BYTES} bytes"))
        }
        val manifest = runCatching { decode(text) }.getOrElse {
            return Result.failure(IllegalArgumentException("invalid vod-catalog-relay JSON: ${it.message}"))
        }
        return validate(manifest, installedVersionName, cachedVersion)
    }

    fun validate(
        manifest: VodCatalogRelayManifest,
        installedVersionName: String,
        cachedVersion: Int,
    ): Result<VodCatalogRelayManifest> {
        if (manifest.version < 1) {
            return Result.failure(IllegalArgumentException("version must be >= 1"))
        }
        if (manifest.version < cachedVersion) {
            return Result.failure(IllegalArgumentException("version ${manifest.version} older than cached $cachedVersion"))
        }
        val minApp = manifest.minAppVersion?.trim().orEmpty()
        if (minApp.isNotEmpty() && isMinAppTooHigh(minApp, installedVersionName)) {
            return Result.failure(
                IllegalArgumentException("minAppVersion $minApp requires newer app than $installedVersionName"),
            )
        }
        val movies = manifest.movies.mapIndexed { index, movie ->
            sanitizeMovie(movie, index).getOrElse { return Result.failure(it) }
        }
        val shows = manifest.shows.mapIndexed { index, show ->
            sanitizeShow(show, index).getOrElse { return Result.failure(it) }
        }
        return Result.success(
            manifest.copy(
                minAppVersion = minApp.takeIf { it.isNotEmpty() },
                message = manifest.message?.trim()?.takeIf { it.isNotEmpty() },
                movies = movies,
                shows = shows,
            ),
        )
    }

    /** Dedup key: tmdbId preferred, else title+year+type. */
    fun movieIdentityKey(movie: VodRelayMovie): String {
        if (movie.tmdbId > 0) return "tmdb:${movie.tmdbId}"
        val title = normalizeTitle(movie.title)
        val year = movie.year?.trim().orEmpty()
        return "title:$title|$year|movie"
    }

    fun showIdentityKey(show: VodRelayShow): String {
        if (show.tmdbId > 0) {
            return "tmdb:${show.tmdbId}:${show.season}:${show.episode}"
        }
        val title = normalizeTitle(show.title)
        return "title:$title|${show.year.orEmpty()}|show|${show.season}|${show.episode}"
    }

    fun normalizeTitle(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("""[^\w\s]"""), " ")
            .replace(Regex("""\b(the|a|an)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun sanitizeMovie(movie: VodRelayMovie, index: Int): Result<VodRelayMovie> {
        if (movie.tmdbId <= 0 && movie.title.isBlank()) {
            return Result.failure(IllegalArgumentException("movies[$index] needs tmdbId or title"))
        }
        val streams = movie.streams.mapIndexed { sIndex, stream ->
            sanitizeStream(stream, "movies[$index].streams[$sIndex]").getOrElse { return Result.failure(it) }
        }
        return Result.success(
            movie.copy(
                title = movie.title.trim(),
                year = movie.year?.trim()?.takeIf { it.isNotEmpty() },
                imdbId = movie.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) },
                overview = movie.overview.trim(),
                posterUrl = movie.posterUrl?.trim()?.takeIf { it.isNotEmpty() },
                streams = streams.distinctBy { it.url },
            ),
        )
    }

    private fun sanitizeShow(show: VodRelayShow, index: Int): Result<VodRelayShow> {
        if (show.tmdbId <= 0 && show.title.isBlank()) {
            return Result.failure(IllegalArgumentException("shows[$index] needs tmdbId or title"))
        }
        if (show.season <= 0 || show.episode <= 0) {
            return Result.failure(IllegalArgumentException("shows[$index] needs season and episode >= 1"))
        }
        val streams = show.streams.mapIndexed { sIndex, stream ->
            sanitizeStream(stream, "shows[$index].streams[$sIndex]").getOrElse { return Result.failure(it) }
        }
        return Result.success(
            show.copy(
                title = show.title.trim(),
                year = show.year?.trim()?.takeIf { it.isNotEmpty() },
                imdbId = show.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) },
                overview = show.overview.trim(),
                posterUrl = show.posterUrl?.trim()?.takeIf { it.isNotEmpty() },
                episodeTitle = show.episodeTitle?.trim()?.takeIf { it.isNotEmpty() },
                streams = streams.distinctBy { it.url },
            ),
        )
    }

    private fun sanitizeStream(stream: VodRelayStream, field: String): Result<VodRelayStream> {
        val url = stream.url.trim()
        if (url.isEmpty()) {
            return Result.failure(IllegalArgumentException("$field url required"))
        }
        val uri = runCatching { URI(url) }.getOrElse {
            return Result.failure(IllegalArgumentException("$field invalid URL"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(IllegalArgumentException("$field must be http(s)"))
        }
        val host = uri.host?.lowercase()?.trim('.')
        if (host.isNullOrBlank() || !HOST_REGEX.matches(host)) {
            return Result.failure(IllegalArgumentException("$field invalid hostname"))
        }
        return Result.success(
            stream.copy(
                url = url,
                quality = stream.quality?.trim()?.takeIf { it.isNotEmpty() },
                label = stream.label?.trim()?.takeIf { it.isNotEmpty() },
                referer = stream.referer?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private fun isMinAppTooHigh(minAppVersion: String, installedVersionName: String): Boolean {
        val minCode = PatchVersionComparator.versionCodeFromPatchName(minAppVersion) ?: return false
        val installedCode = PatchVersionComparator.versionCodeFromPatchName(installedVersionName) ?: return false
        return minCode > installedCode
    }
}
