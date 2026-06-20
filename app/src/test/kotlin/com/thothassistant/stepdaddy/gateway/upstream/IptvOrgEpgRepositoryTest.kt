package com.thothassistant.stepdaddy.gateway.upstream

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvOrgEpgRepositoryTest {
    @Test
    fun `siteForPlaylist resolves explicit and inferred providers`() {
        assertEquals("pluto.tv", IptvOrgEpgConfig.siteForPlaylist("us_pluto.m3u"))
        assertEquals("plex.tv", IptvOrgEpgConfig.siteForPlaylist("ca_plex.m3u"))
        assertEquals("distro.tv", IptvOrgEpgConfig.siteForPlaylist("uk_distro.m3u"))
        assertEquals(null, IptvOrgEpgConfig.siteForPlaylist("custom_feed.m3u"))
    }

    @Test
    fun `mergedGuideFile keeps bundled gzip bytes intact`() {
        val gzipBytes = gzip("guide-body")
        val tempDir = Files.createTempDirectory("iptv-org-epg-gzip").toFile()
        val repository = repositoryFor(tempDir) { assetPath ->
            when (assetPath) {
                IptvOrgEpgRepository.BUNDLED_ASSET_DAT -> ByteArrayInputStream(gzipBytes)
                else -> throw FileNotFoundException(assetPath)
            }
        }

        val merged = requireNotNull(repository.mergedGuideFile())

        assertArrayEquals(gzipBytes, merged.readBytes())
        assertFalse(repository.isStale())
        assertTrue(File(tempDir, "supplement/iptv-org-epg/meta.txt").readText().trim().toLong() > 0L)
        tempDir.deleteRecursively()
    }

    @Test
    fun `mergedGuideFile gzips plain xml fallback asset`() {
        val xmlBytes = "<tv><channel id=\"demo\" /></tv>".toByteArray()
        val tempDir = Files.createTempDirectory("iptv-org-epg-xml").toFile()
        val repository = repositoryFor(tempDir) { assetPath ->
            when (assetPath) {
                IptvOrgEpgRepository.BUNDLED_ASSET_DAT -> ByteArrayInputStream(xmlBytes)
                else -> throw FileNotFoundException(assetPath)
            }
        }

        val merged = requireNotNull(repository.mergedGuideFile())

        assertTrue(merged.length() > 0L)
        assertEquals("<tv><channel id=\"demo\" /></tv>", gunzip(merged))
        tempDir.deleteRecursively()
    }

    @Test
    fun `blank remote refresh falls back to bundled asset`() {
        val gzipBytes = gzip("refresh-guide")
        val tempDir = Files.createTempDirectory("iptv-org-epg-refresh").toFile()
        val repository = repositoryFor(tempDir) { assetPath ->
            when (assetPath) {
                IptvOrgEpgRepository.BUNDLED_ASSET_DAT -> ByteArrayInputStream(gzipBytes)
                else -> throw FileNotFoundException(assetPath)
            }
        }

        repository.refresh("   ")

        val guide = File(tempDir, "supplement/iptv-org-epg/fast-us.xml.gz")
        assertTrue(guide.exists())
        assertArrayEquals(gzipBytes, guide.readBytes())
        tempDir.deleteRecursively()
    }

    private fun repositoryFor(
        filesDir: File,
        assetOpener: (String) -> ByteArrayInputStream,
    ): IptvOrgEpgRepository = IptvOrgEpgRepository(assetOpener = assetOpener, filesDir = filesDir)

    private fun gzip(text: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text.toByteArray()) }
        return output.toByteArray()
    }

    private fun gunzip(file: File): String =
        GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
}
