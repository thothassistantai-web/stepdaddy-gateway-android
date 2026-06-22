package com.thothassistant.stepdaddy.gateway.upstream

/** Visual theme for a Special Events guide category (gradient + accent + watermark). */
data class SpecialEventsGuideTheme(
    val gradientTop: Int,
    val gradientBottom: Int,
    val accent: Int,
    val accentSoft: Int,
    val panel: Int,
    val watermarkEmoji: String,
) {
    companion object {
        fun forCategory(category: String, emoji: String): SpecialEventsGuideTheme {
            val key = category.trim().lowercase()
            return when {
                "mlb" in key || "baseball" in key -> theme(
                    top = rgb(8, 32, 72),
                    bottom = rgb(4, 12, 28),
                    accent = rgb(200, 48, 56),
                    soft = rgb(120, 150, 200),
                    emoji = "⚾",
                )
                "nba" in key || "basketball" in key -> theme(
                    top = rgb(42, 18, 72),
                    bottom = rgb(14, 8, 32),
                    accent = rgb(245, 132, 38),
                    soft = rgb(170, 130, 220),
                    emoji = "🏀",
                )
                "nfl" in key || "football" in key && "soccer" !in key -> theme(
                    top = rgb(12, 42, 28),
                    bottom = rgb(6, 16, 12),
                    accent = rgb(76, 168, 96),
                    soft = rgb(120, 180, 140),
                    emoji = "🏈",
                )
                "soccer" in key || "football (" in key -> theme(
                    top = rgb(10, 48, 36),
                    bottom = rgb(4, 18, 14),
                    accent = rgb(64, 196, 132),
                    soft = rgb(110, 180, 150),
                    emoji = "⚽",
                )
                "nhl" in key || "hockey" in key -> theme(
                    top = rgb(10, 36, 58),
                    bottom = rgb(4, 14, 24),
                    accent = rgb(96, 176, 232),
                    soft = rgb(130, 170, 210),
                    emoji = "🏒",
                )
                "golf" in key -> theme(
                    top = rgb(12, 44, 24),
                    bottom = rgb(5, 18, 10),
                    accent = rgb(96, 196, 108),
                    soft = rgb(130, 180, 130),
                    emoji = "⛳",
                )
                "tennis" in key -> theme(
                    top = rgb(36, 52, 18),
                    bottom = rgb(14, 20, 8),
                    accent = rgb(196, 214, 72),
                    soft = rgb(160, 180, 110),
                    emoji = "🎾",
                )
                "swimming" in key -> theme(
                    top = rgb(8, 44, 72),
                    bottom = rgb(4, 18, 36),
                    accent = rgb(72, 188, 232),
                    soft = rgb(120, 180, 220),
                    emoji = "🏊",
                )
                "boxing" in key || "ufc" in key || "mma" in key -> theme(
                    top = rgb(48, 12, 16),
                    bottom = rgb(18, 6, 8),
                    accent = rgb(232, 88, 72),
                    soft = rgb(180, 110, 110),
                    emoji = "🥊",
                )
                "live event" in key -> theme(
                    top = rgb(52, 10, 14),
                    bottom = rgb(20, 6, 8),
                    accent = rgb(232, 64, 64),
                    soft = rgb(180, 100, 100),
                    emoji = "🔴",
                )
                else -> theme(
                    top = rgb(16, 24, 40),
                    bottom = rgb(8, 12, 22),
                    accent = rgb(91, 159, 212),
                    soft = rgb(130, 150, 180),
                    emoji = emoji.ifBlank { "📅" },
                )
            }
        }

        fun channelRed(color: Int): Int = (color shr 16) and 0xFF
        fun channelGreen(color: Int): Int = (color shr 8) and 0xFF
        fun channelBlue(color: Int): Int = color and 0xFF

        private fun theme(
            top: Int,
            bottom: Int,
            accent: Int,
            soft: Int,
            emoji: String,
        ): SpecialEventsGuideTheme = SpecialEventsGuideTheme(
            gradientTop = top,
            gradientBottom = bottom,
            accent = accent,
            accentSoft = soft,
            panel = argb(210, 12, 18, 30),
            watermarkEmoji = emoji,
        )

        private fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)

        private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
