package com.thothassistant.stepdaddy.gateway.epg

import android.util.Log
import com.thothassistant.stepdaddy.gateway.model.Channel
import com.thothassistant.stepdaddy.gateway.upstream.SupplementSource
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
    private val supplementSource: SupplementSource? = null,
    idBridge: EpgShareIdBridge? = null,
    tvtvFetcher: TvtvUsEpgFetcher? = null,
    builder: LightEpgBuilder? = null,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val buildMutex = Mutex()
  private val epgBuilder = builder ?: LightEpgBuilder(store, idBridge, tvtvFetcher)
  @Volatile
  private var buildInFlight = false

  val meta: EpgMeta
    get() = store.meta

  fun epgReady(): Boolean =
      store.servedXml.exists() && store.meta.programmeCount > 0 && store.meta.builtAtMs > 0L

  fun isBuilding(): Boolean = buildInFlight || store.meta.state == "building"

  fun needsBuild(): Boolean =
      !epgReady() && (store.meta.programmeCount <= 0 || store.meta.state != "ready" || store.isStale())

  fun programmeCount(): Int = store.meta.programmeCount

  fun ageSeconds(): Long? = store.ageSeconds()

  fun isServeStale(): Boolean = store.isServeStale()

  fun scheduleRefresh(channels: List<Channel>, force: Boolean = false) {
    if (channels.isEmpty()) return
    if (buildInFlight) return
    if (!force && epgReady() && !store.isStale() && !store.isServeStale()) return
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
          refresh(channels, force = store.isStale() || store.isServeStale())
        }
        delay(EpgConfig.REBUILD_CHECK_INTERVAL_MS)
      }
    }
  }

  suspend fun refresh(channels: List<Channel>, force: Boolean = false) {
    buildMutex.withLock {
      if (buildInFlight) return
      if (!force && epgReady() && !store.isStale() && !store.isServeStale()) return
      if (!force && !store.isStale() && !store.isServeStale() &&
          store.servedXml.exists() && store.meta.programmeCount > 0
      ) return
      buildInFlight = true
      store.updateState("building")
      val started = System.currentTimeMillis()
      try {
        supplementSource?.prepareFastEpgForBuild()
        supplementSource?.applyNameEpgOverrides()
        val namesById = channels.associate { it.id to it.name }
        val tvgIds = mapper.allTvgIds(channels.map { it.id }, namesById)
        val supplementTvgIds = supplementSource?.tvgIdsForEpg().orEmpty()
        val fastTvgIds = supplementSource?.fastTvgIdsForEpg().orEmpty()
        val iptvOrgTvgIds = supplementSource?.iptvOrgTvgIdsForEpg().orEmpty()
        val sportsTvgIds = supplementSource?.sportsTvgIdsForEpg().orEmpty()
        val channelNamesByTvgId = buildMap<String, String> {
          channels.forEach { ch -> ch.tvgId?.let { put(it, ch.name) } }
          supplementSource?.channels()?.forEach { sup -> sup.tvgId?.let { put(it, sup.name) } }
        }
        val result = withContext(Dispatchers.IO) {
          epgBuilder.build(
              tvgIds = tvgIds,
              supplementEpgFile = supplementSource?.epgXmlFile(),
              supplementTvgIds = supplementTvgIds,
              iptvOrgSupplementTvgIds = iptvOrgTvgIds,
              iptvOrgEpgFile = supplementSource?.iptvOrgEpgFile(),
              sportsEpgFile = supplementSource?.sportsEpgXmlFile(),
              sportsTvgIds = sportsTvgIds,
              fastEpgFiles = supplementSource?.fastEpgFeedFiles().orEmpty(),
              fastEpgTvgIds = fastTvgIds,
              channelNamesByTvgId = channelNamesByTvgId,
              placeholdersEnabled = true,
          )
        }
        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        if (result.programmeCount <= 0 && tvgIds.isNotEmpty()) {
          runCatching { result.outputFile.delete() }
          store.updateState(
              "error",
              "No programme data from feeds (${tvgIds.size} mapped ids)",
          )
          Log.w(TAG, "EPG build produced 0 programmes for ${tvgIds.size} tvg ids in ${elapsed}s — will retry")
          return
        }
        store.writeServedXmlFromFile(
            source = result.outputFile,
            programmeCount = result.programmeCount,
            channelCount = result.channelCount,
            mappedTvgCount = tvgIds.size + supplementTvgIds.size,
            buildSeconds = elapsed,
            realProgrammeCount = result.realProgrammeCount,
            placeholderProgrammeCount = result.placeholderProgrammeCount,
            channelsWithProgrammes = result.channelIdsWithProgrammes.size,
            channelsWithRealProgrammes = result.channelsWithRealProgrammes,
            channelsWithPlaceholders = result.channelsWithPlaceholders,
        )
        Log.i(
            TAG,
            "EPG built: ${result.programmeCount} programmes (${result.realProgrammeCount} real, " +
                "${result.placeholderProgrammeCount} placeholder), ${result.channelCount} channels in ${elapsed}s",
        )
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

  fun servedXmlFile(): java.io.File? =
      store.servedXml.takeIf { it.isFile && it.length() > 0L }

  fun hasCachedProgrammes(): Boolean =
      store.meta.programmeCount > 0 && servedXmlFile() != null

  fun maybeTriggerStaleRefresh(channels: List<Channel>) {
    if (store.isStale() || store.isServeStale()) {
      scheduleRefresh(channels, force = false)
    }
  }

  companion object {
    private const val TAG = "EpgManager"
  }
}
