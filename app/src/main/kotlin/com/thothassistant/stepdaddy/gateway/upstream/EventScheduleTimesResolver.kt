package com.thothassistant.stepdaddy.gateway.upstream

import com.thothassistant.stepdaddy.gateway.model.EventScheduleSource
import com.thothassistant.stepdaddy.gateway.model.EventScheduleTimes
import com.thothassistant.stepdaddy.gateway.model.SupplementChannel

/** Resolves [EventScheduleTimes] from stored channel fields or DaddyLive schedule metadata. */
object EventScheduleTimesResolver {
    fun fromChannel(channel: SupplementChannel): EventScheduleTimes? =
        fromStored(channel.eventStartMs, channel.eventStopMs)
            ?: fromEventSourceUrl(channel.eventSourceUrl)

    fun fromGuideRow(row: SpecialEventsMerger.GuideEventRow): EventScheduleTimes? =
        EventScheduleTimes.ofEpochMs(row.startMs, row.stopMs)

    fun fromDlhdSchedule(dateKey: String, timeLabel: String): EventScheduleTimes? {
        val (start, stop) = DlhdScheduleTime.parseWindow(dateKey, timeLabel)
        return EventScheduleTimes.of("", start, stop, EventScheduleSource.DLHD_TV)
    }

    fun fromStored(startMs: Long?, stopMs: Long?): EventScheduleTimes? =
        if (startMs == null || stopMs == null) null else EventScheduleTimes.ofEpochMs(startMs, stopMs)

    private fun fromEventSourceUrl(raw: String?): EventScheduleTimes? {
        val meta = DlhdEventSourceMeta.parse(raw) ?: return null
        return fromDlhdSchedule(meta.dateKey, meta.timeLabel)
    }
}
