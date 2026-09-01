package com.wafflehq.commander.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHistoryRepositoryTest {

    @Test
    fun `decodeDownloadVersions returns an empty list for null or blank input`() {
        assertTrue(decodeDownloadVersions(null).isEmpty())
        assertTrue(decodeDownloadVersions("").isEmpty())
        assertTrue(decodeDownloadVersions("   ").isEmpty())
    }

    @Test
    fun `decodeDownloadVersions returns an empty list for unparseable input instead of throwing`() {
        assertTrue(decodeDownloadVersions("not-json").isEmpty())
    }

    @Test
    fun `encodeDownloadVersions round-trips through decodeDownloadVersions`() {
        val versions = listOf(
            DownloadVersion("periodical::debug-apk", "2026-08-26T00:00:00.000Z", "2026-08-26T00:00:05.000Z", "/tmp/app-debug.apk"),
            DownloadVersion("periodical::debug-apk", null, "2026-08-27T00:00:05.000Z", "/tmp/app-debug-2.apk"),
        )

        val result = decodeDownloadVersions(encodeDownloadVersions(versions))

        assertEquals(versions, result)
    }

    @Test
    fun `applyDownloadVersion appends a new version for an unseen key`() {
        val newEntry = DownloadVersion("periodical::debug-apk", "ts1", "2026-08-26T00:00:00Z", "/tmp/app-1.apk")

        val (updated, evicted) = applyDownloadVersion(emptyList(), newEntry)

        assertEquals(listOf(newEntry), updated)
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun `applyDownloadVersion keeps versions of other keys untouched`() {
        val other = DownloadVersion("other::file", "ts", "2026-08-26T00:00:00Z", "/tmp/other.apk")
        val newEntry = DownloadVersion("periodical::debug-apk", "ts1", "2026-08-26T00:00:00Z", "/tmp/app-1.apk")

        val (updated, evicted) = applyDownloadVersion(listOf(other), newEntry)

        assertEquals(listOf(other, newEntry), updated)
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun `applyDownloadVersion keeps up to three versions per key without evicting`() {
        val v1 = DownloadVersion("periodical::debug-apk", "ts1", "2026-08-26T00:00:00Z", "/tmp/app-1.apk")
        val v2 = DownloadVersion("periodical::debug-apk", "ts2", "2026-08-27T00:00:00Z", "/tmp/app-2.apk")
        val v3 = DownloadVersion("periodical::debug-apk", "ts3", "2026-08-28T00:00:00Z", "/tmp/app-3.apk")

        val (afterV2, evicted2) = applyDownloadVersion(listOf(v1), v2)
        val (afterV3, evicted3) = applyDownloadVersion(afterV2, v3)

        assertEquals(listOf(v1, v2, v3), afterV3)
        assertTrue(evicted2.isEmpty())
        assertTrue(evicted3.isEmpty())
    }

    @Test
    fun `applyDownloadVersion evicts the oldest version once more than three exist for a key`() {
        val v1 = DownloadVersion("periodical::debug-apk", "ts1", "2026-08-26T00:00:00Z", "/tmp/app-1.apk")
        val v2 = DownloadVersion("periodical::debug-apk", "ts2", "2026-08-27T00:00:00Z", "/tmp/app-2.apk")
        val v3 = DownloadVersion("periodical::debug-apk", "ts3", "2026-08-28T00:00:00Z", "/tmp/app-3.apk")
        val v4 = DownloadVersion("periodical::debug-apk", "ts4", "2026-08-29T00:00:00Z", "/tmp/app-4.apk")

        val (updated, evicted) = applyDownloadVersion(listOf(v1, v2, v3), v4)

        assertEquals(listOf(v2, v3, v4), updated)
        assertEquals(listOf(v1), evicted)
    }

    @Test
    fun `applyDownloadVersion respects a custom maxPerKey`() {
        val v1 = DownloadVersion("periodical::debug-apk", "ts1", "2026-08-26T00:00:00Z", "/tmp/app-1.apk")
        val v2 = DownloadVersion("periodical::debug-apk", "ts2", "2026-08-27T00:00:00Z", "/tmp/app-2.apk")

        val (updated, evicted) = applyDownloadVersion(listOf(v1), v2, maxPerKey = 1)

        assertEquals(listOf(v2), updated)
        assertEquals(listOf(v1), evicted)
    }
}
