package com.wafflehq.appgetter.ui.collections

import com.wafflehq.appgetter.data.api.CollectedFile
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStateTest {

    private val file = CollectedFile("test.apk", "2026-08-26T00:00:00.000Z")

    @Test
    fun `a file never downloaded is NOT_DOWNLOADED`() {
        assertEquals(DownloadState.NOT_DOWNLOADED, downloadState(file, emptyMap()))
    }

    @Test
    fun `a file downloaded at the current server timestamp is UP_TO_DATE`() {
        val downloaded = mapOf("test.apk" to file.timestamp)
        assertEquals(DownloadState.UP_TO_DATE, downloadState(file, downloaded))
    }

    @Test
    fun `a file downloaded at an older timestamp is UPDATE_AVAILABLE`() {
        val downloaded = mapOf("test.apk" to "2026-08-25T00:00:00.000Z")
        assertEquals(DownloadState.UPDATE_AVAILABLE, downloadState(file, downloaded))
    }

    @Test
    fun `entries for other files are ignored`() {
        val downloaded = mapOf("other.apk" to file.timestamp)
        assertEquals(DownloadState.NOT_DOWNLOADED, downloadState(file, downloaded))
    }
}
