package com.thothassistant.stepdaddy.gateway.upstream

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.charset.Charset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun OkHttpClient.executeAsync(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        response.close()
                    }
                }
            },
        )
    }

class HttpStatusException(
    val code: Int,
    val url: HttpUrl,
    val responseMessage: String? = null,
) : IOException("HTTP $code for $url")

class BodyTooLargeException(
    val url: HttpUrl,
    val maxBytes: Long,
) : IOException("body exceeds ${maxBytes}B for $url")

suspend fun OkHttpClient.getText(
    request: Request,
    maxBytes: Long = GatewayConfig.DEFAULT_GET_TEXT_MAX_BYTES,
): String {
    executeAsync(request).use { response ->
        if (!response.isSuccessful) {
            // Drain little / nothing — avoid buffering error HTML (daddylive.li 404 ≈ 18KB+).
            response.body?.close()
            throw HttpStatusException(
                code = response.code,
                url = response.request.url,
                responseMessage = response.message,
            )
        }
        val body = response.body ?: return ""
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            body.close()
            throw BodyTooLargeException(response.request.url, maxBytes)
        }
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (!source.exhausted()) {
            val read = source.read(buffer, 8192L)
            if (read < 0L) break
            total += read
            if (total > maxBytes) {
                buffer.clear()
                throw BodyTooLargeException(response.request.url, maxBytes)
            }
        }
        val charset: Charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return buffer.readString(charset)
    }
}
