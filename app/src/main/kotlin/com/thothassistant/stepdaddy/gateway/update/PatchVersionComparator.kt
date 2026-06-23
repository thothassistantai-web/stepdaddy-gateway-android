package com.thothassistant.stepdaddy.gateway.update

/**
 * Compares TiviMate Daddy [patchVersion] strings and optional [versionCode] values.
 *
 * Canonical ordering uses numeric [versionCode] when both sides have one (from manifest
 * or parsed `major.minor.patch` in the patch name). Falls back to normalized name compare.
 */
object PatchVersionComparator {
    private val NUMERIC_PATCH = Regex("""(\d+)\.(\d+)\.(\d+)""")

    fun versionCodeFromPatchName(patchVersion: String): Int? {
        val match = NUMERIC_PATCH.find(patchVersion.trim()) ?: return null
        val (major, minor, patch) = match.destructured
        return major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    }

    /**
     * @return positive when [latestVersionCode] is newer than installed, negative when older, 0 when equal
     */
    fun compare(
        installedPatchVersion: String?,
        installedVersionCode: Int?,
        latestPatchVersion: String,
        latestVersionCode: Int,
    ): Int {
        val installedCode = installedVersionCode
            ?: installedPatchVersion?.let(::versionCodeFromPatchName)
        if (installedCode != null && latestVersionCode > 0) {
            return latestVersionCode.compareTo(installedCode)
        }
        return normalize(latestPatchVersion).compareTo(normalize(installedPatchVersion.orEmpty()))
    }

    fun isUpdateAvailable(
        installedPatchVersion: String?,
        installedVersionCode: Int?,
        latestPatchVersion: String,
        latestVersionCode: Int,
    ): Boolean = compare(
        installedPatchVersion,
        installedVersionCode,
        latestPatchVersion,
        latestVersionCode,
    ) > 0

    private fun normalize(patchVersion: String): String =
        patchVersion.trim().lowercase().removePrefix("v")
}
