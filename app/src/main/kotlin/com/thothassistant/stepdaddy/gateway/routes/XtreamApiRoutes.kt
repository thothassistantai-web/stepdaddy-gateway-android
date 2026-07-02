package com.thothassistant.stepdaddy.gateway.routes

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.upstream.DaddyLiveClient
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
import com.thothassistant.stepdaddy.gateway.upstream.TmdbVodConfig
import com.thothassistant.stepdaddy.gateway.upstream.VodCategoryResolver
import com.thothassistant.stepdaddy.gateway.xtream.XtreamLiveCatalog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Xtream Codes-compatible API for TiviMate / IPTV Smarters browse mode.
 * Live channels via get_live_* ; movies/series via get_vod_* / get_series_*.
 */
class XtreamApiRoutes(
    private val environment: GatewayEnvironment,
    private val client: DaddyLiveClient,
    private val supplementSource: SupplementSource,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun playerApi(call: ApplicationCall) {
        val username = call.request.queryParameters["username"].orEmpty()
        val password = call.request.queryParameters["password"].orEmpty()
        if (!environment.isXtreamAuthorized(username, password)) {
            call.respondText("""{"user_info":{"auth":0}}""", ContentType.Application.Json, HttpStatusCode.OK)
            return
        }
        val action = call.request.queryParameters["action"].orEmpty()
        val base = environment.loopbackBase().trimEnd('/')
        val payload = when (action) {
            "get_live_categories" -> withContext(Dispatchers.Default) {
                json.encodeToString(
                    XtreamLiveCatalog.categories(client.channels, supplementSource.channels()),
                )
            }
            "get_live_streams" -> withContext(Dispatchers.Default) {
                json.encodeToString(
                    XtreamLiveCatalog.streams(
                        client.channels,
                        supplementSource.channels(),
                        base,
                    ).filter { stream ->
                        val categoryId = call.request.queryParameters["category_id"]
                        categoryId.isNullOrBlank() || stream.category_id == categoryId
                    },
                )
            }
            "get_vod_categories" -> vodPayload()
            "get_vod_streams" -> vodStreamsPayload(base, call.request.queryParameters["category_id"])
            "get_vod_info" -> vodInfo(call.request.queryParameters["vod_id"])
            "get_series_categories" -> seriesPayload()
            "get_series" -> seriesListPayload(call.request.queryParameters["category_id"])
            "get_series_info" -> json.encodeToString(seriesInfo(base, call.request.queryParameters["series_id"]))
            "" -> userInfo()
            else -> """{"error":"unknown_action"}"""
        }
        call.respondText(payload, ContentType.Application.Json)
    }

    private fun vodPayload(): String {
        if (!environment.supplementTmdbMoviesEnabled) return "[]"
        return json.encodeToString(vodCategories())
    }

    private fun vodStreamsPayload(base: String, categoryId: String?): String {
        if (!environment.supplementTmdbMoviesEnabled) return "[]"
        return json.encodeToString(vodStreams(base, categoryId))
    }

    private fun seriesPayload(): String {
        if (!environment.supplementTmdbMoviesEnabled) return "[]"
        return json.encodeToString(seriesCategories())
    }

    private fun seriesListPayload(categoryId: String?): String {
        if (!environment.supplementTmdbMoviesEnabled) return "[]"
        return json.encodeToString(seriesList(categoryId))
    }

    @Serializable
    data class XtreamCategory(
        val category_id: String,
        val category_name: String,
        val parent_id: Int = 0,
    )

    @Serializable
    data class XtreamVodStream(
        val num: Int,
        val name: String,
        val stream_type: String = "movie",
        val stream_id: Int,
        val stream_icon: String? = null,
        val rating: String = "",
        val tmdb: String = "",
        val category_id: String = "",
        val container_extension: String = "mp4",
    )

    @Serializable
    data class XtreamSeries(
        val num: Int,
        val name: String,
        val series_id: Int,
        val cover: String? = null,
        val plot: String? = null,
        val genre: String? = null,
        val category_id: String = "",
        val tmdb: String = "",
    )

    @Serializable
    data class XtreamSeriesInfo(
        val seasons: List<XtreamSeason>,
        val info: XtreamSeries,
        val episodes: Map<String, List<XtreamEpisode>>,
    )

    @Serializable
    data class XtreamSeason(val season_number: Int, val name: String = "")

    @Serializable
    data class XtreamEpisode(
        val id: String,
        val episode_num: Int,
        val title: String,
        val container_extension: String = "mp4",
        val season: Int,
        val direct_source: String = "",
    )

    private fun userInfo(): String = """{
        "user_info": {
            "auth": 1,
            "status": "Active",
            "username": "${environment.xtreamUsername}",
            "server_info": {
                "url": "${environment.loopbackBase()}",
                "port": "${environment.port}",
                "server_protocol": "http"
            }
        }
    }"""

    private fun vodCategories(): List<XtreamCategory> =
        movieChannels()
            .map { it.groupTitle }
            .distinct()
            .sorted()
            .map { title ->
                XtreamCategory(
                    category_id = VodCategoryResolver.categoryId(title),
                    category_name = title,
                )
            }

    private fun seriesCategories(): List<XtreamCategory> =
        seriesChannels()
            .map { it.groupTitle }
            .distinct()
            .sorted()
            .map { title ->
                XtreamCategory(
                    category_id = VodCategoryResolver.categoryId(title),
                    category_name = title,
                )
            }

    private fun vodStreams(base: String, categoryId: String?): List<XtreamVodStream> {
        val movies = movieChannels().filter { ch ->
            categoryId.isNullOrBlank() ||
                VodCategoryResolver.categoryId(ch.groupTitle) == categoryId
        }
        return movies.mapIndexed { index, ch ->
            val tmdbId = TmdbVodConfig.tmdbIdFromSupplementId(ch.id)?.toIntOrNull() ?: 0
            XtreamVodStream(
                num = index + 1,
                name = ch.name,
                stream_id = tmdbId,
                stream_icon = ch.logo,
                tmdb = tmdbId.toString(),
                category_id = VodCategoryResolver.categoryId(ch.groupTitle),
            )
        }
    }

    private fun vodInfo(vodId: String?): String {
        val id = vodId?.trim()?.toIntOrNull() ?: return """{"info":{}}"""
        val ch = movieChannels().firstOrNull { it.id == TmdbVodConfig.supplementId(id) }
            ?: return """{"info":{}}"""
        val plot = ch.plot?.replace("\"", "\\\"").orEmpty()
        val name = ch.name.replace("\"", "\\\"")
        val logo = ch.logo.orEmpty()
        return """{
            "info": {
                "name": "$name",
                "plot": "$plot",
                "movie_image": "$logo",
                "cover_big": "$logo",
                "tmdb_id": "$id"
            },
            "movie_data": {
                "stream_id": $id,
                "name": "$name",
                "container_extension": "mp4"
            }
        }"""
    }

    private fun seriesList(categoryId: String?): List<XtreamSeries> {
        val byShow = seriesChannels().groupBy { ch ->
            TmdbVodConfig.parseSeriesSupplementId(ch.id)?.showTmdbId
        }.filterKeys { it != null }
        return byShow.entries.mapIndexed { index, (showId, episodes) ->
            val first = episodes.first()
            XtreamSeries(
                num = index + 1,
                name = first.name.substringBefore(" - S").trim(),
                series_id = showId!!,
                cover = first.logo,
                plot = first.plot,
                category_id = VodCategoryResolver.categoryId(first.groupTitle),
                tmdb = showId.toString(),
            )
        }.filter { s ->
            categoryId.isNullOrBlank() ||
                s.category_id == categoryId
        }
    }

    private fun seriesInfo(base: String, seriesId: String?): XtreamSeriesInfo {
        val showTmdbId = seriesId?.trim()?.toIntOrNull() ?: return XtreamSeriesInfo(
            seasons = emptyList(),
            info = XtreamSeries(0, "", 0),
            episodes = emptyMap(),
        )
        val episodes = seriesChannels().filter { ch ->
            TmdbVodConfig.parseSeriesSupplementId(ch.id)?.showTmdbId == showTmdbId
        }
        val first = episodes.firstOrNull()
        val showName = first?.name?.substringBefore(" - S")?.trim().orEmpty()
        val info = XtreamSeries(
            num = 1,
            name = showName,
            series_id = showTmdbId,
            cover = first?.logo,
            plot = first?.plot,
            category_id = first?.let { VodCategoryResolver.categoryId(it.groupTitle) }.orEmpty(),
            tmdb = showTmdbId.toString(),
        )
        val bySeason = episodes.mapNotNull { ch ->
            TmdbVodConfig.parseSeriesSupplementId(ch.id)
        }.groupBy { it.season }
        val seasonList = bySeason.keys.sorted().map { XtreamSeason(it) }
        val episodeMap = bySeason.mapValues { (_, keys) ->
            keys.sortedBy { it.episode }.map { key ->
                XtreamEpisode(
                    id = "${key.showTmdbId}_${key.season}_${key.episode}",
                    episode_num = key.episode,
                    title = episodes.first { TmdbVodConfig.parseSeriesSupplementId(it.id) == key }.name,
                    season = key.season,
                    direct_source = "${base.trimEnd('/')}/vod/series/${key.showTmdbId}/${key.season}/${key.episode}.m3u8",
                )
            }
        }.mapKeys { it.key.toString() }
        return XtreamSeriesInfo(seasons = seasonList, info = info, episodes = episodeMap)
    }

    private fun movieChannels() =
        supplementSource.channels().filter { it.id.startsWith(TmdbVodConfig.ID_PREFIX) }

    private fun seriesChannels() =
        supplementSource.channels().filter { it.id.startsWith(TmdbVodConfig.SERIES_ID_PREFIX) }
}
