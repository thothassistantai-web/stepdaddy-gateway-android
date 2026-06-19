package com.thothassistant.stepdaddy.gateway.sidecar

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class EmbeddedSidecarRepository(context: Context) {
    data class Snapshot(
        val playlistBody: String,
        val moveOnJoyChannels: Int,
        val fetchedAtMs: Long,
        val sourceBytes: Int,
    )

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "embedded-sidecar").also { it.mkdirs() }
    private val playlistFile = File(dir, "playlist.m3u8")
    private val epgGzipFile = File(dir, "xmltv.xml.gz")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(SidecarConfig.DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SidecarConfig.DOWNLOAD_TIMEOUT_MS + 10_000L, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var snapshot: Snapshot? = null

    init {
        loadDiskCache()
    }

    fun playlistBody(): String = snapshot?.playlistBody ?: emptyPlaylist()

    fun epgGzipFile(): File? = epgGzipFile.takeIf { it.isFile && it.length() > 0L }

    fun channelCount(): Int = snapshot?.moveOnJoyChannels ?: 0

    fun schedulePeriodicRefresh() {
        scope.launch {
            delay(15_000)
            refresh(force = true)
            while (isActive) {
                delay(SidecarConfig.SYNC_INTERVAL_MS)
                refresh(force = false)
            }
        }
    }

    suspend fun refresh(force: Boolean = false) {
        refreshMutex.withLock {
            val ageMs = System.currentTimeMillis() - (snapshot?.fetchedAtMs ?: 0L)
            if (!force && snapshot != null && ageMs < SidecarConfig.SYNC_INTERVAL_MS) {
                return
            }
            withContext(Dispatchers.IO) {
                runCatching { fetchAndBuild() }
                    .onSuccess { built ->
                        snapshot = built
                        persist(built)
                        Log.i(
                            TAG,
                            "Embedded sidecar refreshed: ${built.moveOnJoyChannels} MoveOnJoy channels " +
                                "(${built.playlistBody.length} bytes playlist)",
                        )
                    }
                    .onFailure { exc ->
                        Log.w(TAG, "Embedded sidecar refresh failed — keeping cache", exc)
                        if (snapshot == null) {
                            snapshot = Snapshot(
                                playlistBody = emptyPlaylist(),
                                moveOnJoyChannels = 0,
                                fetchedAtMs = 0L,
                                sourceBytes = 0,
                            )
                        }
                    }
            }
        }
    }

    private fun fetchAndBuild(): Snapshot {
        val request = Request.Builder()
            .url(SidecarConfig.FORMATTED_PLAYLIST_URL)
            .header("User-Agent", SidecarConfig.USER_AGENT)
            .get()
            .build()
        val raw = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("upstream_http_${response.code}")
            }
            val body = response.body ?: error("upstream_empty_body")
            val bytes = body.bytes()
            if (bytes.size > SidecarConfig.MAX_PLAYLIST_BYTES) {
                error("upstream_too_large")
            }
            bytes.toString(Charsets.UTF_8)
        }
        val playlist = MoveOnJoyPlaylistBuilder.fromFormattedPlaylist(raw)
        val count = MoveOnJoyPlaylistBuilder.countMoveOnJoyEntries(raw)
        writeMinimalEpgGzip()
        return Snapshot(
            playlistBody = playlist,
            moveOnJoyChannels = count,
            fetchedAtMs = System.currentTimeMillis(),
            sourceBytes = raw.length,
        )
    }

    private fun writeMinimalEpgGzip() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<tv generator-info-name="StepDaddy Embedded Sidecar"/>
""".trim()
        val tmp = File(dir, "xmltv.xml.gz.part")
        tmp.outputStream().use { fileOut ->
            GZIPOutputStream(fileOut).use { gzip ->
                gzip.write(xml.toByteArray(Charsets.UTF_8))
            }
        }
        if (!tmp.renameTo(epgGzipFile)) {
            epgGzipFile.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }

    private fun persist(built: Snapshot) {
        playlistFile.writeText(built.playlistBody)
    }

    private fun loadDiskCache() {
        if (!playlistFile.isFile) return
        val body = runCatching { playlistFile.readText() }.getOrNull() ?: return
        if (body.isBlank()) return
        snapshot = Snapshot(
            playlistBody = body,
            moveOnJoyChannels = body.lineSequence().count { MoveOnJoyPlaylistBuilder.isMoveOnJoyStream(it) },
            fetchedAtMs = playlistFile.lastModified(),
            sourceBytes = body.length,
        )
    }

    private fun emptyPlaylist(): String =
        MoveOnJoyPlaylistBuilder.fromFormattedPlaylist("")

    companion object {
        private const val TAG = "EmbeddedSidecar"
    }
}
