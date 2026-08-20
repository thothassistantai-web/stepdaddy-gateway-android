package com.thothassistant.stepdaddy.gateway.upstream

import android.util.Log
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.epg.SpecialEventsEpgGenerator
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel
import java.io.File

/** DLHD schedule resolve + Special Events XMLTV write helpers used by [SupplementSource]. */
internal object SupplementSpecialEventsSupport {
    private const val TAG = "SupplementSource"

    fun resolveDlhdScheduleEvents(
        primaryBase: String,
        environment: GatewayEnvironment,
        dlhdEventResolver: DaddyLiveEventResolver,
    ): Pair<List<DaddyLiveEventResolver.ParsedEvent>, DaddyLiveEventResolver.ResolveStats> {
        val mirrorBases = linkedSetOf<String>()
        mirrorBases += primaryBase.trimEnd('/')
        mirrorBases += environment.dlhdBaseUrl.trimEnd('/')
        environment.mirrorUrls.forEach { mirrorBases += it.trimEnd('/') }

        for (base in mirrorBases) {
            if (base.isEmpty()) continue
            val (events, stats) = dlhdEventResolver.resolveFromNetwork(base)
            if (events.isEmpty()) {
                Log.d(TAG, "DLHD schedule empty on $base (tv=${stats.tvEvents} tv2=${stats.tv2Events})")
                continue
            }
            Log.i(
                TAG,
                "DLHD schedule from $base: tv=${stats.tvEvents} tv2=${stats.tv2Events} links=${stats.streamLinks}",
            )
            return events to stats
        }
        return emptyList<DaddyLiveEventResolver.ParsedEvent>() to DaddyLiveEventResolver.ResolveStats()
    }

    fun rewriteSportsEpg(
        sportsEpgFile: File,
        channels: List<SupplementChannel>,
        programmes: Map<String, List<SpecialEventsMerger.GuideEventRow>>,
    ) {
        if (channels.isEmpty()) {
            sportsEpgFile.delete()
            return
        }
        runCatching {
            SpecialEventsEpgGenerator.writeXml(
                SpecialEventsEpgGenerator.programmesForBundle(channels, programmes),
                sportsEpgFile,
            )
        }.onFailure { exc ->
            Log.w(TAG, "Special Events EPG write failed", exc)
        }
    }

    fun fetchBundle(
        scheduleBase: String,
        environment: GatewayEnvironment,
        dlhdEventResolver: DaddyLiveEventResolver,
    ): Pair<DaddyLiveEventResolver.ResolveStats, SpecialEventsMerger.EpgBundle> {
        val (dlhdEvents, dlhdStats) = runCatching {
            resolveDlhdScheduleEvents(scheduleBase, environment, dlhdEventResolver)
        }.getOrElse { exc ->
            Log.w(TAG, "DLHD schedule resolve failed (base=$scheduleBase)", exc)
            emptyList<DaddyLiveEventResolver.ParsedEvent>() to DaddyLiveEventResolver.ResolveStats()
        }
        val rawBundle = SpecialEventsMerger.buildFromParsed(
            dlhdEvents = dlhdEvents,
            dlhdStats = dlhdStats,
            maxStreams = SupplementConfig.MAX_SPECIAL_EVENT_STREAMS,
        )
        return dlhdStats to EventLifecycleManager.dedupeBundle(rawBundle)
    }
}
