package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Base64
import android.util.Log
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves TMDB movie ids to playable HLS/MP4 URLs via vidsrc-embed.ru (StreamFlix-compatible).
 */
class VidsrcMovieResolver(
    private val httpClient: OkHttpClient,
) {
    data class ResolvedStream(
        val url: String,
        val referer: String,
        val isHls: Boolean,
    )

    fun resolveMovie(tmdbId: String): ResolvedStream {
        val embedUrl = "${TmdbVodConfig.VIDSRC_EMBED_BASE}/embed/movie?tmdb=${tmdbId.trim()}"
        val iframeDoc = httpGet(embedUrl, referer = TmdbVodConfig.VIDSRC_REFERER)
        val iframeSrc = Regex("""<iframe[^>]+id=["']player_iframe["'][^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(iframeDoc)?.groupValues?.get(1)
            ?.let { normalizeUrl(it, embedUrl) }
            ?: Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(iframeDoc)?.groupValues?.get(1)
                ?.let { normalizeUrl(it, embedUrl) }
            ?: error("vidsrc_iframe_missing")

        val playerPage = httpGet(iframeSrc, referer = embedUrl)
        val prorcpPath = Regex("""src:\s*'(/prorcp/[^']+)'""")
            .find(playerPage)?.groupValues?.get(1)
            ?: error("vidsrc_prorcp_missing")
        val prorcpUrl = iframeSrc.substringBefore("/rcp") + prorcpPath
        val script = httpGet(prorcpUrl, referer = iframeSrc)

        val playerId = Regex("""Playerjs.*file:\s*([a-zA-Z0-9]+)\s*,""")
            .find(script)?.groupValues?.get(1).orEmpty()

        val streamUrl = if (playerId.isNotBlank()) {
            val encrypted = Regex("""<div id="$playerId" style="display:none;">\s*(.*?)\s*</div>""", RegexOption.DOT_MATCHES_ALL)
                .find(script)?.groupValues?.get(1)
                ?: error("vidsrc_encrypted_missing")
            decrypt(playerId, encrypted)
        } else {
            Regex("""Playerjs.*file:\s*"([^"]+)"\s*,""")
                .find(script)?.groupValues?.get(1)
                ?: error("vidsrc_file_missing")
        }

        val cleaned = streamUrl.split(" or ")
            .firstOrNull()
            ?.replace(Regex("""\{[a-z]\d+\}"""), "quibblezoomfable.com")
            ?.trim()
            .orEmpty()
        if (cleaned.isEmpty()) error("vidsrc_stream_empty")

        val isHls = cleaned.contains(".m3u8", ignoreCase = true)
        return ResolvedStream(
            url = cleaned,
            referer = iframeSrc,
            isHls = isHls,
        )
    }

    private fun httpGet(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun normalizeUrl(url: String, base: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        url.startsWith("/") -> {
            val uri = URI(base)
            "${uri.scheme}://${uri.host}$url"
        }
        else -> url
    }

    private fun decrypt(id: String, encrypted: String): String = when (id) {
        "NdonQLf1Tzyx7bMG" -> chunkReverse(encrypted, 3)
        "sXnL9MQIry" -> sXnL9MQIry(encrypted)
        "IhWrImMIGL" -> IhWrImMIGL(encrypted)
        "xTyBxQyGTA" -> xTyBxQyGTA(encrypted)
        "ux8qjPHC66" -> ux8qjPHC66(encrypted)
        "eSfH1IRMyL" -> eSfH1IRMyL(encrypted)
        "KJHidj7det" -> KJHidj7det(encrypted)
        "o2VSUnjnZl" -> o2VSUnjnZl(encrypted)
        "Oi3v1dAlaM" -> Oi3v1dAlaM(encrypted)
        "TsA2KGDGux" -> TsA2KGDGux(encrypted)
        "JoAHUMCLXV" -> JoAHUMCLXV(encrypted)
        else -> error("vidsrc_decrypt_unsupported:$id")
    }

    private fun chunkReverse(a: String, chunk: Int): String =
        a.indices.step(chunk).map { a.substring(it, minOf(it + chunk, a.length)) }.reversed().joinToString("")

    private fun sXnL9MQIry(a: String): String {
        val key = "pWB9V)[*4I`nJpp?ozyB~dbr9yt!_n4u"
        val d = a.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        var c = ""
        for (i in d.indices) c += (d[i].code xor key[i % key.length].code).toChar()
        var e = ""
        for (ch in c) e += (ch.code - 3).toChar()
        return String(Base64.decode(e, Base64.DEFAULT))
    }

    private fun IhWrImMIGL(a: String): String {
        val b = a.reversed().map { ch ->
            when {
                ch in 'a'..'m' || ch in 'A'..'M' -> (ch.code + 13).toChar()
                ch in 'n'..'z' || ch in 'N'..'Z' -> (ch.code - 13).toChar()
                else -> ch
            }
        }.joinToString("").reversed()
        return String(Base64.decode(b, Base64.DEFAULT))
    }

    private fun xTyBxQyGTA(a: String): String {
        val c = a.reversed().filterIndexed { index, _ -> index % 2 == 0 }
        return Base64.decode(c, Base64.DEFAULT).toString(Charsets.UTF_8)
    }

    private fun ux8qjPHC66(a: String): String {
        val b = a.reversed()
        val c = "X9a(O;FMV2-7VO5x;Ao\u0005:dN1NoFs?j,"
        val d = b.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        var e = ""
        for (i in d.indices) e += (d[i].code xor c[i % c.length].code).toChar()
        return e
    }

    private fun eSfH1IRMyL(a: String): String {
        val b = a.reversed().map { (it.code - 1).toChar() }.joinToString("")
        return b.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
    }

    private fun KJHidj7det(a: String): String {
        val b = a.substring(10, a.length - 16)
        val c = "3SAY~#%Y(V%>5d/Yg\"\$G[Lh1rK4a;7ok"
        val d = String(Base64.decode(b, Base64.DEFAULT))
        val e = c.repeat((d.length + c.length - 1) / c.length).take(d.length)
        var f = ""
        for (i in d.indices) f += (d[i].code xor e[i].code).toChar()
        return f
    }

    private fun o2VSUnjnZl(a: String): String = a.map { char ->
        when (char) {
            in 'a'..'z' -> {
                val shifted = char - 3
                if (shifted < 'a') shifted + 26 else shifted
            }
            in 'A'..'Z' -> {
                val shifted = char - 3
                if (shifted < 'A') shifted + 26 else shifted
            }
            else -> char
        }
    }.joinToString("")

    private fun Oi3v1dAlaM(a: String): String {
        val c = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.decode(c, Base64.DEFAULT))
        var e = ""
        for (ch in d) e += (ch.code - 5).toChar()
        return e
    }

    private fun TsA2KGDGux(a: String): String {
        val c = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.decode(c, Base64.DEFAULT))
        var e = ""
        for (ch in d) e += (ch.code - 7).toChar()
        return e
    }

    private fun JoAHUMCLXV(a: String): String {
        val c = a.reversed().replace("-", "+").replace("_", "/")
        val d = String(Base64.decode(c, Base64.DEFAULT))
        var e = ""
        for (ch in d) e += (ch.code - 3).toChar()
        return e
    }

    companion object {
        private const val TAG = "VidsrcMovieResolver"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
