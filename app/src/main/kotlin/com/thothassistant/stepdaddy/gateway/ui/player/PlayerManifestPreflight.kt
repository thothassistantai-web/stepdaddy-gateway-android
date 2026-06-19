package com.thothassistant.stepdaddy.gateway.ui.player

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.ui.dashboard.TuneChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PlayerManifestPreflight {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(
        environment: GatewayEnvironment,
        channel: TuneChannel,
    ): PlayerErrorState? = withContext(Dispatchers.IO) {
        val url = PlayerHttpHeaders.streamUrl(environment, channel.id)
        val request = PlayerHttpHeaders.applyToRequest(Request.Builder().url(url), environment)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val manifestMessage = HlsErrorManifestParser.extractMessage(body)
                if (!response.isSuccessful || manifestMessage != null) {
                    return@withContext PlayerErrorMapper.fromPreflight(
                        httpStatus = response.code,
                        body = body,
                        throwable = null,
                    )
                }
                null
            }
        }.getOrElse { throwable ->
            PlayerErrorMapper.fromPreflight(
                httpStatus = null,
                body = "",
                throwable = throwable,
            )
        }
    }
}
