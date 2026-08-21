package com.thothassistant.stepdaddy.gateway.relay

import com.thothassistant.stepdaddy.gateway.update.PatchVersionComparator
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeParseException

object DomainRelayValidator {
    const val MAX_BYTES = 32 * 1024

    private val HOST_REGEX = Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$""")

    fun parseAndValidate(
        text: String,
        installedVersionName: String,
        cachedVersion: Int,
        decode: (String) -> DomainRelayManifest,
    ): Result<DomainRelayManifest> {
        if (text.length > MAX_BYTES) {
            return Result.failure(IllegalArgumentException("domain-relay exceeds ${MAX_BYTES} bytes"))
        }
        val manifest = runCatching { decode(text) }.getOrElse {
            return Result.failure(IllegalArgumentException("invalid domain-relay JSON: ${it.message}"))
        }
        return validate(manifest, installedVersionName, cachedVersion)
    }

    fun validate(
        manifest: DomainRelayManifest,
        installedVersionName: String,
        cachedVersion: Int,
    ): Result<DomainRelayManifest> {
        if (manifest.version < 1) {
            return Result.failure(IllegalArgumentException("version must be >= 1"))
        }
        if (manifest.version < cachedVersion) {
            return Result.failure(IllegalArgumentException("version ${manifest.version} older than cached $cachedVersion"))
        }
        val minApp = manifest.minAppVersion?.trim().orEmpty()
        if (minApp.isNotEmpty() && isMinAppTooHigh(minApp, installedVersionName)) {
            return Result.failure(
                IllegalArgumentException("minAppVersion $minApp requires newer app than $installedVersionName"),
            )
        }
        val source = manifest.daddylive()
            ?: return Result.failure(IllegalArgumentException("sources.daddylive required"))
        validateOptionalUrl(source.primary, "primary").onFailure { return Result.failure(it) }
        source.mirrors.forEachIndexed { index, url ->
            validateOptionalUrl(url, "mirrors[$index]").onFailure { return Result.failure(it) }
        }
        source.relayHosts.forEachIndexed { index, url ->
            validateOptionalUrl(url, "relayHosts[$index]").onFailure { return Result.failure(it) }
        }
        source.embedHosts.forEachIndexed { index, url ->
            validateOptionalUrl(url, "embedHosts[$index]").onFailure { return Result.failure(it) }
        }
        source.blocked.forEachIndexed { index, host ->
            val normalized = normalizeHostname(host)
                ?: return Result.failure(IllegalArgumentException("blocked[$index] invalid hostname"))
            if (!HOST_REGEX.matches(normalized)) {
                return Result.failure(IllegalArgumentException("blocked[$index] invalid hostname"))
            }
        }
        return Result.success(sanitize(manifest))
    }

    fun isForceUpdateDue(forceUpdateAfter: String?, now: LocalDate = LocalDate.now()): Boolean {
        val raw = forceUpdateAfter?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return try {
            !LocalDate.parse(raw.take(10)).isAfter(now)
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private fun isMinAppTooHigh(minAppVersion: String, installedVersionName: String): Boolean {
        val minCode = PatchVersionComparator.versionCodeFromPatchName(minAppVersion) ?: return false
        val installedCode = PatchVersionComparator.versionCodeFromPatchName(installedVersionName) ?: return false
        return minCode > installedCode
    }

    private fun validateOptionalUrl(raw: String?, field: String): Result<Unit> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return Result.success(Unit)
        val uri = runCatching { URI(value) }.getOrElse {
            return Result.failure(IllegalArgumentException("$field is not a valid URL"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(IllegalArgumentException("$field must be http(s)"))
        }
        val host = uri.host?.lowercase()?.trim('.')
        if (host.isNullOrBlank() || !HOST_REGEX.matches(host)) {
            return Result.failure(IllegalArgumentException("$field has invalid hostname"))
        }
        return Result.success(Unit)
    }

    private fun sanitize(manifest: DomainRelayManifest): DomainRelayManifest {
        val source = manifest.daddylive() ?: return manifest
        val cleaned = source.copy(
            primary = source.primary?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
            mirrors = source.mirrors.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct(),
            blocked = source.blocked.mapNotNull { normalizeHostname(it) }.distinct(),
            relayHosts = source.relayHosts.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct(),
            embedHosts = source.embedHosts.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct(),
        )
        return manifest.copy(
            minAppVersion = manifest.minAppVersion?.trim()?.takeIf { it.isNotEmpty() },
            forceUpdateAfter = manifest.forceUpdateAfter?.trim()?.takeIf { it.isNotEmpty() },
            message = manifest.message?.trim()?.takeIf { it.isNotEmpty() },
            sources = manifest.sources + ("daddylive" to cleaned),
        )
    }

    private fun normalizeHostname(raw: String): String? {
        val trimmed = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val host = trimmed.substringBefore('/').substringBefore(':').trim('.')
        return host.takeIf { it.isNotEmpty() }
    }
}
