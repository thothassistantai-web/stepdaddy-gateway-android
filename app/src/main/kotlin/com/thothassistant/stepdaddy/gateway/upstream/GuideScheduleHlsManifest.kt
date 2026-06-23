package com.thothassistant.stepdaddy.gateway.upstream

/** HLS EVENT wrapper so TiviMate lists guide slates as live TV, not VOD/Movies (.mp4). */
object GuideScheduleHlsManifest {
    fun build(mp4Url: String, segmentSeconds: Int = GuideScheduleMp4Encoder.SLATE_DURATION_SEC): String {
        val duration = segmentSeconds.coerceAtLeast(1)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine("#EXT-X-TARGETDURATION:$duration")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:EVENT")
            appendLine("#EXTINF:$duration.0,schedule")
            appendLine(mp4Url.trim())
            appendLine("#EXT-X-ENDLIST")
        }
    }
}
