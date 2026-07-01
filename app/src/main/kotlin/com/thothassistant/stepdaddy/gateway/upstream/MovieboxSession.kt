package com.thothassistant.stepdaddy.gateway.upstream

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP session for Moviebox mirrors with cookie bootstrap (moviebox-js-sdk compatible).
 */
class MovieboxSession(
    private val httpClient: OkHttpClient,
) {
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    @Volatile
    private var cookiesReady = false
    @Volatile
    private var mirrorIndex = 0

    private val client: OkHttpClient = httpClient.newBuilder()
        .cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore[url.host].orEmpty()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (cookies.isEmpty()) return
                val bucket = cookieStore.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { fresh ->
                    bucket.removeAll { it.name == fresh.name }
                    bucket.add(fresh)
                }
            }
        })
        .build()

    fun baseUrl(): String {
        val host = MovieboxConfig.MIRROR_HOSTS[mirrorIndex.coerceIn(
            MovieboxConfig.MIRROR_HOSTS.indices,
        )]
        return "https://$host/"
    }

    fun buildUrl(path: String, query: Map<String, String> = emptyMap()): String {
        val builder = baseUrl().toHttpUrl().newBuilder()
            .addPathSegments(path.trimStart('/'))
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    fun ensureCookies() {
        if (cookiesReady && cookieStore.isNotEmpty()) return
        var lastError: Exception? = null
        for (offset in MovieboxConfig.MIRROR_HOSTS.indices) {
            mirrorIndex = (mirrorIndex + offset) % MovieboxConfig.MIRROR_HOSTS.size
            val url = buildUrl(
                MovieboxConfig.APP_INFO_PATH,
                mapOf("app_name" to "moviebox"),
            )
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", MovieboxConfig.USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.bytes()
                }
                cookiesReady = true
                return
            }.onFailure { lastError = it as? Exception ?: Exception(it.message) }
        }
        throw lastError ?: error("moviebox_cookie_bootstrap_failed")
    }

    fun getJson(path: String, query: Map<String, String> = emptyMap(), referer: String? = null): String {
        ensureCookies()
        var lastError: Exception? = null
        repeat(MovieboxConfig.MIRROR_HOSTS.size) { attempt ->
            val url = buildUrl(path, query)
            runCatching {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", MovieboxConfig.USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Client-Info", """{"timezone":"UTC"}""")
                if (referer != null) {
                    builder.header("Referer", referer)
                }
                client.newCall(builder.get().build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    return response.body?.string().orEmpty()
                }
            }.onFailure { exc ->
                lastError = exc as? Exception ?: Exception(exc.message)
                mirrorIndex = (mirrorIndex + 1) % MovieboxConfig.MIRROR_HOSTS.size
            }
        }
        throw lastError ?: error("moviebox_request_failed")
    }

    fun postJson(path: String, body: String): String {
        ensureCookies()
        var lastError: Exception? = null
        repeat(MovieboxConfig.MIRROR_HOSTS.size) {
            val url = buildUrl(path)
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", MovieboxConfig.USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Client-Info", """{"timezone":"UTC"}""")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    return response.body?.string().orEmpty()
                }
            }.onFailure { exc ->
                lastError = exc as? Exception ?: Exception(exc.message)
                mirrorIndex = (mirrorIndex + 1) % MovieboxConfig.MIRROR_HOSTS.size
            }
        }
        throw lastError ?: error("moviebox_post_failed")
    }

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
