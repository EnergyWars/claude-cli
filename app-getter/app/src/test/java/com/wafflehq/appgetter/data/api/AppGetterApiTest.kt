package com.wafflehq.appgetter.data.api

import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppGetterApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AppGetterApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AppGetterApi()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `probeStatus returns true for a 204 response`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        val result = api.probeStatus(server.hostName, server.port)

        assertTrue(result)
        assertEquals("/status", server.takeRequest().target)
    }

    @Test
    fun `probeStatus returns false for a non-204 response`() = runBlocking {
        server.enqueue(MockResponse(code = 404))

        assertFalse(api.probeStatus(server.hostName, server.port))
    }

    @Test
    fun `getCollections parses the file list`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"files":[{"name":"test.apk","timestamp":"2026-08-25T19:13:12.620Z"}]}"""),
        )

        val result = api.getCollections(server.hostName, server.port)

        assertEquals(1, result.files.size)
        assertEquals("test.apk", result.files.first().name)
        assertEquals("/collections", server.takeRequest().target)
    }

    @Test
    fun `downloadCollectionFile writes the response body using the Content-Disposition filename`(): Unit = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = okhttp3.Headers.headersOf("Content-Disposition", "attachment; filename=\"test.apk\""),
                body = "apk-bytes",
            ),
        )
        val destinationDir = File.createTempFile("appgetter-test", "").also { it.delete(); it.mkdirs() }

        val file = api.downloadCollectionFile(server.hostName, server.port, "test.apk", destinationDir)

        assertEquals("test.apk", file.name)
        assertEquals("apk-bytes", file.readText())
        assertEquals("/collections/get/test.apk", server.takeRequest().target)
    }

    @Test
    fun `getCollections throws an ApiException with the server error message`() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = """{"error":"nicht gefunden"}"""))

        try {
            api.getCollections(server.hostName, server.port)
            throw AssertionError("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(404, error.httpCode)
            assertEquals("nicht gefunden", error.message)
        }
    }
}
