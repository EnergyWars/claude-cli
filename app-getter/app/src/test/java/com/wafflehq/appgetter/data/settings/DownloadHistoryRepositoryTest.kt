package com.wafflehq.appgetter.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHistoryRepositoryTest {

    @Test
    fun `parsePendingInstalls pairs matching timestamp and path keys`() {
        val raw = mapOf(
            "pending_ts::test.apk" to "2026-08-26T00:00:00.000Z",
            "pending_path::test.apk" to "/data/user/0/app/files/Download/test.apk",
        )

        val result = parsePendingInstalls(raw)

        assertEquals(
            listOf(PendingInstall("test.apk", "2026-08-26T00:00:00.000Z", "/data/user/0/app/files/Download/test.apk")),
            result,
        )
    }

    @Test
    fun `parsePendingInstalls ignores unrelated preference keys`() {
        val raw = mapOf(
            "test.apk" to "2026-08-26T00:00:00.000Z",
            "pending_ts::test.apk" to "2026-08-26T00:00:00.000Z",
            "pending_path::test.apk" to "/tmp/test.apk",
        )

        val result = parsePendingInstalls(raw)

        assertEquals(1, result.size)
        assertEquals("test.apk", result.single().fileName)
    }

    @Test
    fun `parsePendingInstalls skips entries missing their counterpart key`() {
        val raw = mapOf(
            "pending_ts::orphan.apk" to "2026-08-26T00:00:00.000Z",
            "pending_path::other.apk" to "/tmp/other.apk",
        )

        val result = parsePendingInstalls(raw)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parsePendingInstalls returns nothing for an empty map`() {
        assertTrue(parsePendingInstalls(emptyMap()).isEmpty())
    }
}
