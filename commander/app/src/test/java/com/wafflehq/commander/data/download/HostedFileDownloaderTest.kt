package com.wafflehq.commander.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedFileDownloaderTest {

    @Test
    fun `recognizes an apk file by its extension`() {
        assertTrue(isApkFileName("app-debug.apk"))
    }

    @Test
    fun `recognizes an apk file regardless of case`() {
        assertTrue(isApkFileName("app-release.APK"))
    }

    @Test
    fun `rejects a file that merely contains apk in its name`() {
        assertFalse(isApkFileName("myapp.apk.txt"))
    }

    @Test
    fun `rejects a file without an extension`() {
        assertFalse(isApkFileName("apk"))
    }

    @Test
    fun `rejects an unrelated extension`() {
        assertFalse(isApkFileName("notes.txt"))
    }
}
