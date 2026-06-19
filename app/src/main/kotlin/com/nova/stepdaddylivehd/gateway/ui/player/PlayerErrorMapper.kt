package com.nova.stepdaddylivehd.gateway.ui.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED
import androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
import androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
import androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
import androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object PlayerErrorMapper {
    fun fromPlaybackException(error: PlaybackException, manifestHint: String? = null): PlayerErrorState {
        val httpStatus = error.errorCode == ERROR_CODE_IO_BAD_HTTP_STATUS
        val statusCode = if (httpStatus) {
            error.cause?.message?.let { parseHttpStatus(it) }
        } else {
            null
        }
        val detailFromManifest = manifestHint?.takeIf { it.isNotBlank() }
        val causeMessage = error.cause?.message.orEmpty()
        val message = detailFromManifest
            ?: error.message?.takeIf { it.isNotBlank() }
            ?: causeMessage.takeIf { it.isNotBlank() }
            ?: "Playback could not start"

        return when {
            isGatewayOffline(error) ->
                gatewayOffline(message)
            statusCode == 502 || message.contains("502", ignoreCase = true) ->
                providerIssue("PLY-502", detailFromManifest ?: shorten(message, "Upstream returned 502"))
            statusCode == 503 || message.contains("upstream_busy", ignoreCase = true) ->
                providerIssue("PLY-503", detailFromManifest ?: shorten(message, "Provider temporarily busy"))
            statusCode == 504 || error.errorCode == ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                transient("PLY-TIMEOUT", detailFromManifest ?: shorten(message, "Stream timed out"))
            error.errorCode == ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                networkFailure(message)
            error.errorCode == ERROR_CODE_IO_FILE_NOT_FOUND ||
                message.contains("not found", ignoreCase = true) ->
                noMirrors(detailFromManifest ?: shorten(message, "Channel or stream not found"))
            isManifestError(error) ->
                manifestError(detailFromManifest ?: shorten(message, "Could not read stream manifest"))
            isTransient(error, message) ->
                transient("PLY-TIMEOUT", detailFromManifest ?: shorten(message, "Temporary stream issue"))
            else ->
                playbackFailed("PLY-UNKNOWN", shorten(message, "Playback failed"))
        }
    }

    fun fromPreflight(
        httpStatus: Int?,
        body: String,
        throwable: Throwable?,
    ): PlayerErrorState {
        val manifestMessage = HlsErrorManifestParser.extractMessage(body)
        return when {
            throwable is ConnectException ||
                throwable?.cause is ConnectException ||
                throwable?.message?.contains("Failed to connect", ignoreCase = true) == true ||
                throwable?.message?.contains("Connection refused", ignoreCase = true) == true ->
                gatewayOffline(throwable?.message)
            httpStatus == 404 ->
                noMirrors(manifestMessage ?: "Channel not found")
            httpStatus == 502 ->
                providerIssue("PLY-502", manifestMessage ?: "Upstream returned 502")
            httpStatus == 503 ->
                providerIssue("PLY-503", manifestMessage ?: "Provider temporarily unavailable")
            httpStatus == 504 || httpStatus == 408 ->
                transient("PLY-TIMEOUT", manifestMessage ?: "Upstream timeout")
            manifestMessage != null ->
                providerIssue("PLY-MANIFEST", manifestMessage)
            httpStatus != null && httpStatus >= 400 ->
                providerIssue("PLY-${httpStatus}", manifestMessage ?: "HTTP $httpStatus from gateway")
            throwable is SocketTimeoutException ->
                transient("PLY-TIMEOUT", "Manifest request timed out")
            throwable is UnknownHostException ->
                networkFailure(throwable.message)
            throwable != null ->
                playbackFailed("PLY-UNKNOWN", shorten(throwable.message.orEmpty(), "Could not load stream"))
            else ->
                playbackFailed("PLY-UNKNOWN", "Could not load stream")
        }
    }

    private fun gatewayOffline(raw: String?): PlayerErrorState =
        PlayerErrorState(
            code = "PLY-NO_GATEWAY",
            title = "Gateway offline",
            detail = shorten(raw, "StepDaddy Gateway is not running on this device"),
            recoverable = true,
        )

    private fun providerIssue(code: String, detail: String): PlayerErrorState =
        PlayerErrorState(
            code = code,
            title = "Provider issue",
            detail = detail,
            recoverable = true,
        )

    private fun transient(code: String, detail: String): PlayerErrorState =
        PlayerErrorState(
            code = code,
            title = "Playback failed",
            detail = detail,
            recoverable = true,
        )

    private fun networkFailure(raw: String?): PlayerErrorState =
        PlayerErrorState(
            code = "PLY-NETWORK",
            title = "Playback failed",
            detail = shorten(raw, "Network connection failed"),
            recoverable = true,
        )

    private fun noMirrors(detail: String): PlayerErrorState =
        PlayerErrorState(
            code = "PLY-NO_MIRRORS",
            title = "Provider issue",
            detail = detail,
            recoverable = true,
        )

    private fun manifestError(detail: String): PlayerErrorState =
        PlayerErrorState(
            code = "PLY-MANIFEST",
            title = "Playback failed",
            detail = detail,
            recoverable = true,
        )

    private fun playbackFailed(code: String, detail: String): PlayerErrorState =
        PlayerErrorState(
            code = code,
            title = "Playback failed",
            detail = detail,
            recoverable = true,
        )

    private fun isGatewayOffline(error: PlaybackException): Boolean {
        val msg = buildString {
            append(error.message.orEmpty())
            append(' ')
            append(error.cause?.message.orEmpty())
            var cause = error.cause
            repeat(3) {
                cause = cause?.cause
                append(' ')
                append(cause?.message.orEmpty())
            }
        }
        return msg.contains("Connection refused", ignoreCase = true) ||
            msg.contains("Failed to connect", ignoreCase = true) ||
            msg.contains("ECONNREFUSED", ignoreCase = true)
    }

    private fun isManifestError(error: PlaybackException): Boolean =
        error.errorCode == ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
            error.errorCode == ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE

    private fun isTransient(error: PlaybackException, message: String): Boolean =
        error.errorCode == ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == ERROR_CODE_IO_UNSPECIFIED && error.cause is SocketTimeoutException ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ||
            message.contains("upstream", ignoreCase = true)

    private fun parseHttpStatus(message: String): Int? {
        val match = Regex("""(?:Response\s+code:\s*|HTTP\s+)(\d{3})""", RegexOption.IGNORE_CASE)
            .find(message)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun shorten(raw: String?, fallback: String): String {
        val cleaned = raw?.replace("\n", " ")?.trim().orEmpty()
        if (cleaned.isEmpty()) return fallback
        return cleaned.take(160)
    }
}
