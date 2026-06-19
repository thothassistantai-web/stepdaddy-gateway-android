package com.nova.stepdaddylivehd.gateway.ui.dashboard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class GatewayMessage(
    val timestampMs: Long,
    val level: String,
    val text: String,
) {
    fun formatLine(): String {
        val time = TIME_FORMAT.format(Date(timestampMs))
        return "[$time] $text"
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

object GatewayMessageBus {
    private const val MAX_MESSAGES = 500
    private val buffer = ArrayDeque<GatewayMessage>(MAX_MESSAGES)
    private val listeners = CopyOnWriteArrayList<(List<GatewayMessage>) -> Unit>()
    private var lastPostKey: String? = null

    @Synchronized
    fun post(text: String, level: String = "INFO") {
        val key = "$level:$text"
        if (key == lastPostKey) return
        lastPostKey = key
        append(GatewayMessage(System.currentTimeMillis(), level, text))
    }

    @Synchronized
    fun postBoot(source: String) {
        post("Boot event: $source", "BOOT")
    }

    @Synchronized
    fun postReady(baseUrl: String) {
        post("Gateway ready at $baseUrl", "READY")
    }

    @Synchronized
    fun postInstallProgress(appName: String, stage: String) {
        post("Install $appName: $stage", "INSTALL")
    }

    @Synchronized
    fun snapshot(): List<GatewayMessage> = buffer.toList()

    fun addListener(listener: (List<GatewayMessage>) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (List<GatewayMessage>) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun append(message: GatewayMessage) {
        while (buffer.size >= MAX_MESSAGES) {
            buffer.removeFirst()
        }
        buffer.addLast(message)
        val copy = buffer.toList()
        listeners.forEach { runCatching { it(copy) } }
    }
}
