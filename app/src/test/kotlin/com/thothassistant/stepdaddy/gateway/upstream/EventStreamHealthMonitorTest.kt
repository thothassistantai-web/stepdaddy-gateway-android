package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventStreamHealthMonitorTest {
    private var changed = 0

    private fun monitor(
        store: DlhdEventStreamHealthStore = DlhdEventStreamHealthStore(),
        channels: () -> List<SupplementChannel> = { emptyList() },
        prober: DlhdEventStreamProber = DlhdEventStreamProber(),
        now: Instant = Instant.now(),
    ): Pair<EventStreamHealthMonitor, DlhdEventStreamHealthStore> {
        changed = 0
        val monitor = EventStreamHealthMonitor(
            channelProvider = channels,
            store = store,
            prober = prober,
            onStatesChanged = { changed++ },
            nowProvider = { now },
        )
        return monitor to store
    }

    @Before
    fun resetCounter() {
        changed = 0
    }

    @Test
    fun `probeEvent marks ended grace without http`() = runBlocking {
        val (event, start) = liveEvent()
        val stop = start.plusSeconds(2 * 60 * 60)
        val now = stop.plusSeconds(5 * 60)
        val (monitor, store) = monitor(now = now)
        val result = monitor.probeEvent(event, tvStreamProbe = { true }, now = now)
        assertEquals(DlhdEventStreamHealth.Status.ENDED, result?.status)
    }

    @Test
    fun `probeEvent skips before live window`() = runBlocking {
        val (event, start) = liveEvent()
        val now = start.minusSeconds(30 * 60)
        val (monitor, store) = monitor(now = now)
        assertNull(monitor.probeEvent(event, tvStreamProbe = { true }, now = now))
        assertEquals(DlhdEventStreamHealth.Status.UNKNOWN, store.status("abc"))
    }

    @Test
    fun `runProbeCycle records healthy tv stream`() = runBlocking {
        val (event, start) = liveEvent()
        val now = start.plusSeconds(30 * 60)
        val (monitor, store) = monitor(channels = { listOf(event) }, now = now)
        monitor.start { channelId ->
            assertEquals("201", channelId)
            true
        }
        monitor.runProbeCycle()
        assertEquals(DlhdEventStreamHealth.Status.HEALTHY, store.status("abc"))
        assertTrue(changed >= 1)
        monitor.stop()
    }

    @Test
    fun `runProbeCycle records ended for grace events`() = runBlocking {
        val (event, start) = liveEvent()
        val stop = start.plusSeconds(2 * 60 * 60)
        val now = stop.plusSeconds(10 * 60)
        val (monitor, store) = monitor(channels = { listOf(event) }, now = now)
        monitor.start { true }
        monitor.runProbeCycle()
        assertEquals(DlhdEventStreamHealth.Status.ENDED, store.status("abc"))
        monitor.stop()
    }

    @Test
    fun `mock webserver healthy hls chain`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val manifestUrl = server.url("/live/index.m3u8").toString()
            val media = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                seg001.ts
            """.trimIndent()
            server.enqueue(MockResponse().setBody(media))
            server.enqueue(MockResponse().setResponseCode(200))

            val httpClient = OkHttpClient.Builder().build()
            val resolver = DlhdEventStreamResolver(
                httpClient = httpClient,
                manifestUrlOverride = { streamKey ->
                    if (streamKey.startsWith("tv2|")) manifestUrl else null
                },
            )
            val prober = DlhdEventStreamProber(resolver = resolver, httpClient = httpClient)
            val (baseEvent, start) = liveEvent()
            val event = baseEvent.copy(dlhdEventStreamKey = "tv2|test-channel")
            val now = start.plusSeconds(30 * 60)
            val (monitor, store) = monitor(
                channels = { listOf(event) },
                prober = prober,
                now = now,
            )
            monitor.start { false }
            monitor.runProbeCycle()
            assertEquals(DlhdEventStreamHealth.Status.HEALTHY, store.status("abc"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `mock webserver unreachable segment is unhealthy`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val manifestUrl = server.url("/live/index.m3u8").toString()
            val media = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                seg001.ts
            """.trimIndent()
            server.enqueue(MockResponse().setBody(media))
            server.enqueue(MockResponse().setResponseCode(404))

            val httpClient = OkHttpClient.Builder().build()
            val resolver = DlhdEventStreamResolver(
                httpClient = httpClient,
                manifestUrlOverride = { streamKey ->
                    if (streamKey.startsWith("tv2|")) manifestUrl else null
                },
            )
            val prober = DlhdEventStreamProber(resolver = resolver, httpClient = httpClient)
            val (baseEvent, start) = liveEvent()
            val event = baseEvent.copy(dlhdEventStreamKey = "tv2|test-channel")
            val now = start.plusSeconds(30 * 60)
            val (monitor, store) = monitor(
                channels = { listOf(event) },
                prober = prober,
                now = now,
            )
            monitor.start { false }
            monitor.runProbeCycle()
            assertEquals(DlhdEventStreamHealth.Status.UNHEALTHY, store.status("abc"))
            assertEquals("segment_unreachable", store.entry("abc")?.lastError)
        } finally {
            server.shutdown()
        }
    }

    private fun liveEvent(): Pair<SupplementChannel, Instant> {
        val (start, _) = DlhdScheduleTime.parseWindow("27th June 2026", "14:00")
        val event = SupplementChannel(
            id = "dlhd-event:abc",
            name = "Final Heat",
            groupTitle = GroupTitleResolver.SPECIAL_EVENTS,
            streamUrl = "",
            providerTag = "SWIMMING",
            eventSourceUrl = "Swimming|27th June 2026|14:00|Swimming : Final Heat",
            dlhdEventStreamKey = "tv|201",
            eventStartMs = start.toEpochMilli(),
            eventStopMs = start.plusSeconds(2 * 60 * 60).toEpochMilli(),
        )
        return event to start
    }
}
