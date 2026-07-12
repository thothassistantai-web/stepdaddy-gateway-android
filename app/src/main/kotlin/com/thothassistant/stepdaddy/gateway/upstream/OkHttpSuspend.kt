package com.thothassistant.stepdaddy.gateway.upstream

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
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

suspend fun OkHttpClient.getText(request: Request): String {
    executeAsync(request).use { response ->
        if (!response.isSuccessful) {
            throw HttpStatusException(
                code = response.code,
                url = response.request.url,
                responseMessage = response.message,
            )
        }
        return response.body?.string().orEmpty()
    }
}
