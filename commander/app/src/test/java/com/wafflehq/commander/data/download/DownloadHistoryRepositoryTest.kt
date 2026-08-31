package com.wafflehq.commander.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHistoryRepositoryTest {

    @Test
    fun `parsePendingInstalls extracts the key from a pending path entry`() {
        val raw = mapOf(
            "pending_path::periodical::debug-apk" to "/data/user/0/app/files/Download/app-debug.apk",
        )

        val result = parsePendingInstalls(raw)

        assertEquals(
            listOf(PendingInstall("periodical::debug-apk", null, "/data/user/0/app/files/Download/app-debug.apk")),
            result,
        )
    }

    @Test
    fun `parsePendingInstalls joins the stored timestamp onto the matching path entry`() {
        val raw = mapOf(
            "pending_path::periodical::debug-apk" to "/tmp/app-debug.apk",
            "pending_ts::periodical::debug-apk" to "2026-08-26T00:00:00.000Z",
        )

        val result = parsePendingInstalls(raw)

        assertEquals(
            listOf(PendingInstall("periodical::debug-apk", "2026-08-26T00:00:00.000Z", "/tmp/app-debug.apk")),
            result,
        )
    }

    @Test
    fun `parsePendingInstalls ignores unrelated preference keys`() {
        val raw = mapOf(
            "debug-apk" to "/tmp/app-debug.apk",
            "pending_path::periodical::debug-apk" to "/tmp/app-debug.apk",
        )

        val result = parsePendingInstalls(raw)

        assertEquals(1, result.size)
        assertEquals("periodical::debug-apk", result.single().key)
    }

    @Test
    fun `parsePendingInstalls returns nothing for an empty map`() {
        assertTrue(parsePendingInstalls(emptyMap()).isEmpty())
    }
}
