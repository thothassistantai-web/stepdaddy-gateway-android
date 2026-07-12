package com.thothassistant.stepdaddy.gateway.upstream

import okhttp3.HttpUrl

object DaddyLiveErrorClassifier {
    fun isTransientError(exc: Exception): Boolean {
        val message = exc.message.orEmpty()
        if (message == "upstream_busy") return true
        if (message.contains("timeout", ignoreCase = true)) return true
        if (message.contains("timed out", ignoreCase = true)) return true
        return false
    }

    fun isCdnFetchError(exc: Exception): Boolean {
        val status = exc as? HttpStatusException
        if (status != null) {
            return isXameleonUrl(status.url)
        }
        val message = exc.message.orEmpty()
        return message.contains("xameleon", ignoreCase = true)
    }

    fun isConnectivityFailure(exc: Exception): Boolean {
        val message = exc.message.orEmpty()
        if (message.contains("resportz watch", ignoreCase = true)) return false
        if (message.contains("failed to connect", ignoreCase = true)) return true
        if (message.contains("unable to resolve host", ignoreCase = true)) return true
        if (message.contains("connection reset", ignoreCase = true)) return true
        if (message.contains("network is unreachable", ignoreCase = true)) return true
        if (message.contains("timeout", ignoreCase = true)) return true
        return false
    }

    /** Only resportz watch / mirror API faults should poison the shared mirror pool. */
    fun shouldMarkMirrorDead(exc: Exception): Boolean {
        if (isCdnFetchError(exc)) return false
        val status = exc as? HttpStatusException
        if (status != null && status.code == 403 && !isXameleonUrl(status.url)) return true
        val message = exc.message.orEmpty()
        if (message.contains("HTTP 403") && !message.contains("xameleon", ignoreCase = true)) return true
        if (message.contains("resportz watch", ignoreCase = true)) return false
        if (message.startsWith("HTTP ") && message.contains("resportz", ignoreCase = true)) return true
        return false
    }

    /** Resportz scrape misses are per-channel; do not poison the shared mirror pool. */
    fun isChannelSpecificError(exc: Exception): Boolean {
        val status = exc as? HttpStatusException
        if (status != null && status.code == 403 && isXameleonUrl(status.url)) return true
        val message = exc.message.orEmpty()
        if (message.contains("HTTP 403") && message.contains("xameleon", ignoreCase = true)) return true
        if (message.contains("encoded m3u8", ignoreCase = true)) return true
        if (message.contains("iframe source", ignoreCase = true)) return true
        if (message.contains("embed stub host", ignoreCase = true)) return true
        if (message.contains("empty iframe", ignoreCase = true)) return true
        if (message.contains("empty encoded source", ignoreCase = true)) return true
        return false
    }

    /** Only count failures toward per-channel cache purge when the channel's own upstream/CDN broke. */
    fun isChannelSpecificStreamFailure(exc: Exception): Boolean {
        val status = exc as? HttpStatusException
        if (status != null) {
            if (status.code == 403 && isXameleonUrl(status.url)) return true
            if (status.code == 403) return false
        }
        val message = exc.message.orEmpty()
        if (message.contains("No mirrors available", ignoreCase = true)) return false
        if (message.contains("upstream_busy")) return false
        if (message.contains("upstream_outage", ignoreCase = true)) return false
        if (message.contains("upstream_timeout", ignoreCase = true)) return false
        if (message.contains("resportz watch", ignoreCase = true)) return false
        if (message.contains("failed to connect", ignoreCase = true)) return false
        if (message.contains("unable to resolve host", ignoreCase = true)) return false
        if (message.contains("timeout", ignoreCase = true)) return false
        if (message.contains("timed out", ignoreCase = true)) return false
        if (message.contains("stream_not_found", ignoreCase = true)) return false
        if (isChannelSpecificError(exc)) return true
        if (message.contains("HTTP 403") && message.contains("xameleon", ignoreCase = true)) return true
        if (message.contains("HTTP 403")) return false
        if (message.contains("HTTP 502") || message.contains("HTTP 504") || message.contains("HTTP 500")) {
            return true
        }
        return true
    }

    fun shouldPurgeUpstreamCache(reason: Exception?): Boolean {
        if (reason == null) return true
        return isChannelSpecificStreamFailure(reason)
    }

    fun isXameleonUrl(url: HttpUrl): Boolean =
        GatewayConfig.XAMELEON_HOSTS.any { host -> url.host.contains(host, ignoreCase = true) }
}
