package com.thothassistant.stepdaddy.gateway.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class GatewayPeer(
    val ip: String,
    val port: Int,
    val gatewayName: String?,
)

object GatewayPeerScanner {
    private val client = OkHttpClient.Builder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(400, TimeUnit.MILLISECONDS)
        .callTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    suspend fun scan(ownIp: String?, port: Int): List<GatewayPeer> = withContext(Dispatchers.IO) {
        val lanIp = ownIp ?: LanAddressResolver.lanIpv4() ?: return@withContext emptyList()
        val prefix = LanAddressResolver.subnetPrefix(lanIp) ?: return@withContext emptyList()
        val candidates = (1..254)
            .map { host -> "$prefix.$host" }
            .filter { it != lanIp }
        coroutineScope {
            candidates.chunked(32).flatMap { batch ->
                batch.map { ip ->
                    async { probe(ip, port) }
                }.awaitAll().filterNotNull()
            }
        }
    }

    private fun probe(ip: String, port: Int): GatewayPeer? = runCatching {
        val request = Request.Builder()
            .url("http://$ip:$port/health")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            GatewayPeer(ip = ip, port = port, gatewayName = null)
        }
    }.getOrNull()
}
