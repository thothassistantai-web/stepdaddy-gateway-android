package com.thothassistant.stepdaddy.gateway.audio

import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.model.AudioPlaybackPrefs
import kotlin.math.pow

/**
 * User-facing volume normalization + amplification preferences.
 *
 * The gateway HTTP proxy does not decode audio. [volumeNormalization] is exposed to companion
 * players (TiviMate, StreamVault) via `/health`, setup JSON, and plugin playback prepare.
 * [amplificationGainDb] is applied to the embedded dashboard/fullscreen ExoPlayer preview.
 */
object AudioPlaybackSettings {
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f
    const val DEFAULT_GAIN_DB = 0f

    /** Max linear gain for embedded ExoPlayer ([Player.setVolume]). */
    const val MAX_LINEAR_VOLUME = 2f

    fun clampGainDb(raw: Float): Float = raw.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

    fun parseGainDb(raw: String?): Float =
        clampGainDb(raw?.trim()?.toFloatOrNull() ?: DEFAULT_GAIN_DB)

    fun dbToLinear(db: Float): Float = 10.0.pow((clampGainDb(db) / 20.0)).toFloat()

    fun fromEnvironment(environment: GatewayEnvironment): AudioPlaybackPrefs =
        AudioPlaybackPrefs(
            volumeNormalization = environment.volumeNormalizationEnabled,
            amplificationGainDb = environment.amplificationGainDb,
        )

    /**
     * Linear volume for embedded ExoPlayer: amplification gain only.
     * Loudness normalization is delegated to external players via [AudioPlaybackPrefs].
     */
    fun embeddedLinearVolume(environment: GatewayEnvironment): Float =
        embeddedLinearVolume(
            volumeNormalization = environment.volumeNormalizationEnabled,
            amplificationGainDb = environment.amplificationGainDb,
        )

    fun embeddedLinearVolume(
        @Suppress("UNUSED_PARAMETER") volumeNormalization: Boolean,
        amplificationGainDb: Float,
    ): Float {
        val linear = dbToLinear(amplificationGainDb)
        return linear.coerceIn(0f, MAX_LINEAR_VOLUME)
    }

    fun formatGainDbForDisplay(gainDb: Float): String {
        val clamped = clampGainDb(gainDb)
        return if (clamped == clamped.toInt().toFloat()) {
            "${clamped.toInt()} dB"
        } else {
            String.format("%.1f dB", clamped)
        }
    }
}
