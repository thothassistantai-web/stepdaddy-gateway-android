package com.nova.stepdaddylivehd.gateway.epg

import android.util.Log
import com.nova.stepdaddylivehd.gateway.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EpgManager(
    private val store: EpgStore,
    private val mapper: EpgChannelMapper,
    private val builder: LightEpgBuilder = LightEpgBuilder(store),
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val buildMutex = Mutex()
  @Volatile
  private var buildInFlight = false

  val meta: EpgMeta
    get() = store.meta

  fun epgReady(): Boolean =
      store.servedXml.exists() && store.meta.programmeCount > 0 && store.meta.builtAtMs > 0L

  fun programmeCount(): Int = store.meta.programmeCount

  fun ageSeconds(): Long? = store.ageSeconds()

  fun scheduleRefresh(channels: List<Channel>, force: Boolean = false) {
    if (channels.isEmpty()) return
    if (buildInFlight) return
    if (!force && !store.isStale() && store.servedXml.exists()) return
    scope.launch {
      refresh(channels, force = force)
    }
  }

  fun schedulePeriodicRefresh(channelProvider: () -> List<Channel>) {
    scope.launch {
      delay(15_000)
      while (isActive) {
        val channels = channelProvider()
        if (channels.isNotEmpty()) {
          refresh(channels, force = store.isStale())
        }
        delay(EpgConfig.REBUILD_INTERVAL_MS)
      }
    }
  }

  suspend fun refresh(channels: List<Channel>, force: Boolean = false) {
    buildMutex.withLock {
      if (buildInFlight) return
      if (!force && !store.isStale() && store.servedXml.exists()) return
      buildInFlight = true
      store.updateState("building")
      val started = System.currentTimeMillis()
      try {
        val namesById = channels.associate { it.id to it.name }
        val tvgIds = mapper.allTvgIds(channels.map { it.id }, namesById)
        val result = withContext(Dispatchers.IO) {
          builder.build(tvgIds)
        }
        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        store.writeServedXmlFromFile(
            source = result.outputFile,
            programmeCount = result.programmeCount,
            channelCount = result.channelCount,
            mappedTvgCount = tvgIds.size,
            buildSeconds = elapsed,
        )
        Log.i(TAG, "EPG built: ${result.programmeCount} programmes, ${result.channelCount} channels in ${elapsed}s")
      } catch (exc: Throwable) {
        if (exc is OutOfMemoryError) {
          Log.e(TAG, "EPG build OOM — keeping cached epg.xml", exc)
        } else {
          Log.w(TAG, "EPG build failed", exc)
        }
        store.updateState(
            if (store.servedXml.exists()) "ready" else "error",
            exc.message?.take(200) ?: exc.javaClass.simpleName,
        )
      } finally {
        buildInFlight = false
      }
    }
  }

  fun readCachedXml(): ByteArray? = store.readServedXml()

  fun maybeTriggerStaleRefresh(channels: List<Channel>) {
    if (store.isStale()) {
      scheduleRefresh(channels, force = false)
    }
  }

  companion object {
    private const val TAG = "EpgManager"
  }
}
