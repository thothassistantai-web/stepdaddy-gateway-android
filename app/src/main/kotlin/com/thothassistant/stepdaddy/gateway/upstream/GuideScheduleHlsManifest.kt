package com.thothassistant.stepdaddy.gateway.upstream

/**
 * HLS master wrapper so TiviMate lists guide slates as live TV (not VOD/Movies) while ExoPlayer
 * plays the on-device progressive MP4 via EXT-X-STREAM-INF (EVENT media playlists with raw MP4
 * segments trigger Loader$UnexpectedLoaderException on TiViMate 5.x).
 */
object GuideScheduleHlsManifest {
    fun build(mp4Url: String, segmentSeconds: Int = GuideScheduleMp4Encoder.SLATE_DURATION_SEC): String {
        segmentSeconds.coerceAtLeast(1)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
            appendLine(
                "#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,CODECS=\"avc1.42E01E,mp4a.40.2\"",
            )
            appendLine(mp4Url.trim())
        }
    }
}
