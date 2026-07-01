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
    private val isGatewayEpgEnabled: () -> Boolean = { true },
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val buildMutex = Mutex()
  private val epgBuilder = builder ?: LightEpgBuilder(store, idBridge, tvtvFetcher)
  private val tvtvFetcher = tvtvFetcher
  @Volatile
  private var buildInFlight = false
  @Volatile
  private var tvtvFollowUpScheduled = false
  @Volatile
  var buildStartedAtMs: Long = 0L
    private set

  val meta: EpgMeta
    get() = store.meta

  fun gatewayEpgEnabled(): Boolean = isGatewayEpgEnabled()

  fun epgReady(): Boolean =
      isGatewayEpgEnabled() &&
          store.servedXml.exists() &&
          store.meta.programmeCount > 0 &&
          store.meta.builtAtMs > 0L

  fun isBuilding(): Boolean = buildInFlight || store.meta.state == "building"

  fun needsBuild(): Boolean =
      isGatewayEpgEnabled() &&
          !epgReady() &&
          (store.meta.programmeCount <= 0 || store.meta.state != "ready" || store.isStale())

  fun programmeCount(): Int = if (isGatewayEpgEnabled()) store.meta.programmeCount else 0

  fun ageSeconds(): Long? = if (isGatewayEpgEnabled()) store.ageSeconds() else null

  fun isServeStale(): Boolean = isGatewayEpgEnabled() && store.isServeStale()

  fun scheduleRefresh(
      channels: List<Channel>,
      force: Boolean = false,
      tvtvGapFill: Boolean = true,
  ) {
    if (!isGatewayEpgEnabled()) return
    if (channels.isEmpty()) return
    if (buildInFlight) return
    if (!force && epgReady() && !store.isStale() && !store.isServeStale()) return
    scope.launch {
      refresh(channels, force = force, tvtvGapFill = tvtvGapFill)
    }
  }

  fun schedulePeriodicRefresh(channelProvider: () -> List<Channel>) {
    scope.launch {
      delay(15_000)
      while (isActive) {
        if (isGatewayEpgEnabled()) {
          val channels = channelProvider()
          if (channels.isNotEmpty()) {
            refresh(channels, force = store.isStale() || store.isServeStale())
          }
        }
        delay(EpgConfig.REBUILD_CHECK_INTERVAL_MS)
      }
    }
  }

  suspend fun refresh(
      channels: List<Channel>,
      force: Boolean = false,
      tvtvGapFill: Boolean = true,
  ) {
    if (!isGatewayEpgEnabled()) return
    buildMutex.withLock {
      if (buildInFlight) return
      if (!force && epgReady() && !store.isStale() && !store.isServeStale()) return
      if (!force && !store.isStale() && !store.isServeStale() &&
          store.servedXml.exists() && store.meta.programmeCount > 0
      ) return
      buildInFlight = true
      if (buildStartedAtMs <= 0L || store.meta.state != "building") {
        buildStartedAtMs = System.currentTimeMillis()
      }
      store.updateState("building")
      val started = System.currentTimeMillis()
      val useTvtvGapFill = tvtvGapFill && (force || epgReady() || store.servedXml.exists())
      try {
        if (useTvtvGapFill) {
          supplementSource?.prepareFastEpgForBuild()
        }
        supplementSource?.applyNameEpgOverrides()
        val namesById = channels.associate { it.id to it.name }
        val tvgIds = mapper.allTvgIds(channels.map { it.id }, namesById)
        val supplementTvgIds = supplementSource?.tvgIdsForEpg().orEmpty()
        val fastTvgIds = supplementSource?.fastTvgIdsForEpg().orEmpty()
        val iptvOrgTvgIds = supplementSource?.iptvOrgTvgIdsForEpg().orEmpty()
        val sportsTvgIds = supplementSource?.sportsTvgIdsForEpg().orEmpty()
        val xyzStreamsTvgIds = supplementSource?.xyzStreamsTvgIdsForEpg().orEmpty()
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
              xyzStreamsEpgFile = supplementSource?.xyzStreamsEpgXmlFile(),
              xyzStreamsTvgIds = xyzStreamsTvgIds,
              fastEpgFiles = supplementSource?.fastEpgFeedFiles().orEmpty(),
              fastEpgTvgIds = fastTvgIds,
              channelNamesByTvgId = channelNamesByTvgId,
              placeholdersEnabled = true,
              placeholderExcludeIds = sportsTvgIds,
              tvtvGapFillEnabled = useTvtvGapFill,
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
                "${result.placeholderProgrammeCount} placeholder), ${result.channelCount} channels in ${elapsed}s" +
                if (!useTvtvGapFill) " [fast pass, tvtv deferred]" else "",
        )
        buildStartedAtMs = 0L
        if (!useTvtvGapFill && tvtvFetcher != null && result.programmeCount > 0) {
          scheduleTvtvFollowUp(channels)
        }
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

  private fun scheduleTvtvFollowUp(channels: List<Channel>) {
    if (tvtvFollowUpScheduled) return
    tvtvFollowUpScheduled = true
    scope.launch {
      try {
        refresh(channels, force = false, tvtvGapFill = true)
      } finally {
        tvtvFollowUpScheduled = false
      }
    }
  }

  fun readCachedXml(): ByteArray? =
      if (isGatewayEpgEnabled()) store.readServedXml() else null

  fun servedXmlFile(): java.io.File? =
      if (!isGatewayEpgEnabled()) {
          null
      } else {
          store.servedXml.takeIf { it.isFile && it.length() > 0L }
      }

  fun hasCachedProgrammes(): Boolean =
      isGatewayEpgEnabled() && store.meta.programmeCount > 0 && servedXmlFile() != null

  fun maybeTriggerStaleRefresh(channels: List<Channel>) {
    if (!isGatewayEpgEnabled()) return
    if (store.isStale() || store.isServeStale()) {
      scheduleRefresh(channels, force = false)
    }
  }

  companion object {
    private const val TAG = "EpgManager"
  }
}
