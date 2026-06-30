package com.thothassistant.stepdaddy.gateway.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaybackSettingsTest {
    @Test
    fun `clampGainDb enforces min and max`() {
        assertEquals(-12f, AudioPlaybackSettings.clampGainDb(-20f), 0.001f)
        assertEquals(12f, AudioPlaybackSettings.clampGainDb(99f), 0.001f)
        assertEquals(3f, AudioPlaybackSettings.clampGainDb(3f), 0.001f)
    }

    @Test
    fun `parseGainDb handles blank and invalid input`() {
        assertEquals(0f, AudioPlaybackSettings.parseGainDb(null), 0.001f)
        assertEquals(0f, AudioPlaybackSettings.parseGainDb("  "), 0.001f)
        assertEquals(0f, AudioPlaybackSettings.parseGainDb("nope"), 0.001f)
        assertEquals(6f, AudioPlaybackSettings.parseGainDb("6"), 0.001f)
        assertEquals(-12f, AudioPlaybackSettings.parseGainDb("-24"), 0.001f)
    }

    @Test
    fun `dbToLinear converts unity and positive gain`() {
        assertEquals(1f, AudioPlaybackSettings.dbToLinear(0f), 0.001f)
        assertTrue(AudioPlaybackSettings.dbToLinear(6f) > 1f)
        assertTrue(AudioPlaybackSettings.dbToLinear(-6f) < 1f)
    }

    @Test
    fun `embeddedLinearVolume caps at max linear volume`() {
        val linear = AudioPlaybackSettings.embeddedLinearVolume(
            volumeNormalization = true,
            amplificationGainDb = 12f,
        )
        assertEquals(AudioPlaybackSettings.MAX_LINEAR_VOLUME, linear, 0.001f)
    }

    @Test
    fun `embeddedLinearVolume ignores normalization flag for preview gain`() {
        val withNorm = AudioPlaybackSettings.embeddedLinearVolume(
            volumeNormalization = true,
            amplificationGainDb = 0f,
        )
        val withoutNorm = AudioPlaybackSettings.embeddedLinearVolume(
            volumeNormalization = false,
            amplificationGainDb = 0f,
        )
        assertEquals(withoutNorm, withNorm, 0.001f)
    }

    @Test
    fun `formatGainDbForDisplay renders integer dB`() {
        assertEquals("0 dB", AudioPlaybackSettings.formatGainDbForDisplay(0f))
        assertEquals("-6 dB", AudioPlaybackSettings.formatGainDbForDisplay(-6f))
    }
}
