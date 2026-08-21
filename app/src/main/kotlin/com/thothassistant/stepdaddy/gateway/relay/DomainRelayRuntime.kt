package com.thothassistant.stepdaddy.gateway.relay

import com.thothassistant.stepdaddy.gateway.BuildConfig

/**
 * Process-wide overlay applied from a validated [DomainRelayManifest].
 * GatewayConfig / GatewayEnvironment read these when relay is active.
 */
object DomainRelayRuntime {
    @Volatile
    private var applied: AppliedRelay? = null

    val isActive: Boolean
        get() = applied != null

    val version: Int
        get() = applied?.manifest?.version ?: 0

    val sourceLabel: String
        get() = applied?.sourceLabel.orEmpty()

    val fetchedAtMs: Long
        get() = applied?.fetchedAtMs ?: 0L

    val message: String
        get() = applied?.manifest?.message.orEmpty()

    val forceUpdateAfter: String?
        get() = applied?.manifest?.forceUpdateAfter

    val primary: String?
        get() = applied?.manifest?.daddylive()?.primary?.takeIf { it.isNotEmpty() }

    val mirrors: List<String>?
        get() = applied?.manifest?.daddylive()?.mirrors?.takeIf { it.isNotEmpty() }

    val blockedHosts: Set<String>?
        get() = applied?.manifest?.daddylive()?.blocked?.map { it.lowercase() }?.toSet()?.takeIf { it.isNotEmpty() }

    val relayHosts: List<String>?
        get() = applied?.manifest?.daddylive()?.relayHosts?.takeIf { it.isNotEmpty() }

    val embedHosts: List<String>?
        get() = applied?.manifest?.daddylive()?.embedHosts?.takeIf { it.isNotEmpty() }

    fun apply(manifest: DomainRelayManifest, sourceLabel: String, fetchedAtMs: Long = System.currentTimeMillis()) {
        applied = AppliedRelay(manifest, sourceLabel, fetchedAtMs)
    }

    fun clear() {
        applied = null
    }

    fun status(
        userCustomizedPrimary: Boolean,
        userCustomizedMirrors: Boolean,
    ): DomainRelayStatus {
        val current = applied ?: return DomainRelayStatus()
        val source = current.manifest.daddylive()
        val forceDue = DomainRelayValidator.isForceUpdateDue(current.manifest.forceUpdateAfter)
        val differsFromDefaults = differsFromCompiledDefaults(source)
        val usingRelayDomains =
            (!userCustomizedPrimary && !primary.isNullOrBlank()) ||
                (!userCustomizedMirrors && !mirrors.isNullOrEmpty()) ||
                !relayHosts.isNullOrEmpty() ||
                !blockedHosts.isNullOrEmpty() ||
                !embedHosts.isNullOrEmpty()
        // Banner when relay supplies hosts that diverge from APK defaults, or forceUpdateAfter is due.
        val active = usingRelayDomains && (differsFromDefaults || forceDue)
        return DomainRelayStatus(
            active = active,
            version = current.manifest.version,
            source = current.sourceLabel,
            fetchedAtMs = current.fetchedAtMs,
            message = current.manifest.message.orEmpty(),
            forceUpdateDue = forceDue,
            primary = source?.primary.orEmpty(),
            mirrorCount = source?.mirrors?.size ?: 0,
        )
    }

    fun healthSnapshot(
        userCustomizedPrimary: Boolean,
        userCustomizedMirrors: Boolean,
    ): DomainRelayHealth {
        val s = status(userCustomizedPrimary, userCustomizedMirrors)
        return DomainRelayHealth(
            active = s.active,
            version = s.version,
            source = s.source,
            fetchedAtMs = s.fetchedAtMs,
            forceUpdateDue = s.forceUpdateDue,
            primary = s.primary,
            mirrorCount = s.mirrorCount,
        )
    }

    private fun differsFromCompiledDefaults(source: DomainRelaySource?): Boolean {
        if (source == null) return false
        val defaultPrimary = BuildConfig.DEFAULT_DLHD_BASE_URL.trimEnd('/')
        val defaultMirrors = listOf(
            "https://dlstreams.st",
            "https://daddylive.li",
            "https://dlhd.st",
        )
        val defaultBlocked = setOf("daddylive.org")
        val defaultRelay = listOf(
            "https://dlstreams.st",
            "https://dlhd.st",
            "https://dlhd.pk",
        )
        val defaultEmbed = listOf(
            "https://dlstreams.st",
            "https://dlhd.st",
            "https://dlhd.pk",
            "https://dlhd.li",
            "https://dlhd.org",
            "https://daddylive.li",
            "https://daddylive.eu",
            "https://daddylive.at",
        )
        val primaryUrl = source.primary?.trimEnd('/')
        if (!primaryUrl.isNullOrBlank() && primaryUrl != defaultPrimary) return true
        if (source.mirrors.isNotEmpty() &&
            source.mirrors.map { it.trimEnd('/') } != defaultMirrors
        ) {
            return true
        }
        if (source.blocked.isNotEmpty() &&
            source.blocked.map { it.lowercase() }.toSet() != defaultBlocked
        ) {
            return true
        }
        if (source.relayHosts.isNotEmpty() &&
            source.relayHosts.map { it.trimEnd('/') } != defaultRelay
        ) {
            return true
        }
        if (source.embedHosts.isNotEmpty() &&
            source.embedHosts.map { it.trimEnd('/') } != defaultEmbed
        ) {
            return true
        }
        return false
    }

    private data class AppliedRelay(
        val manifest: DomainRelayManifest,
        val sourceLabel: String,
        val fetchedAtMs: Long,
    )
}
