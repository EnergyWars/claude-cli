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
    fun `downloadCollectionFile reports the final progress with the known total size`(): Unit = runBlocking {
        val body = "x".repeat(1_000)
        server.enqueue(MockResponse(code = 200, body = body))
        val destinationDir = File.createTempFile("appgetter-test", "").also { it.delete(); it.mkdirs() }
        val progressUpdates = mutableListOf<DownloadProgress>()

        api.downloadCollectionFile(server.hostName, server.port, "test.apk", destinationDir, onProgress = { progressUpdates.add(it) })

        val last = progressUpdates.last()
        assertEquals(1_000L, last.bytesDownloaded)
        assertEquals(1_000L, last.totalBytes)
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

    @Test
    fun `sendFeedback posts the text and parses the created entry`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 201,
                body = """{"id":1,"text":"Hallo","createdAt":"2026-08-26T00:00:00.000Z","updatedAt":"2026-08-26T00:00:00.000Z"}""",
            ),
        )

        val result = api.sendFeedback(server.hostName, server.port, "Hallo")

        assertEquals(1, result.id)
        assertEquals("Hallo", result.text)
        val request = server.takeRequest()
        assertEquals("/feedback", request.target)
        assertEquals("POST", request.method)
        assertEquals("""{"text":"Hallo"}""", request.body?.utf8())
    }

    @Test
    fun `sendFeedback posts the section when given`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 201,
                body = """{"id":2,"text":"Hallo","section":"periodical-debug","createdAt":"2026-08-26T00:00:00.000Z","updatedAt":"2026-08-26T00:00:00.000Z"}""",
            ),
        )

        val result = api.sendFeedback(server.hostName, server.port, "Hallo", "periodical-debug")

        assertEquals("periodical-debug", result.section)
        val request = server.takeRequest()
        assertEquals("""{"text":"Hallo","section":"periodical-debug"}""", request.body?.utf8())
    }

    @Test
    fun `sendFeedback posts the context when given`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 201,
                body = """{"id":3,"text":"Hallo","section":"periodical-debug","context":"periodical-debug.apk (2026-08-26T10:00:00.000Z)","createdAt":"2026-08-26T00:00:00.000Z","updatedAt":"2026-08-26T00:00:00.000Z"}""",
            ),
        )

        val result = api.sendFeedback(
            server.hostName,
            server.port,
            "Hallo",
            "periodical-debug",
            "periodical-debug.apk (2026-08-26T10:00:00.000Z)",
        )

        assertEquals("periodical-debug.apk (2026-08-26T10:00:00.000Z)", result.context)
        val request = server.takeRequest()
        assertEquals(
            """{"text":"Hallo","section":"periodical-debug","context":"periodical-debug.apk (2026-08-26T10:00:00.000Z)"}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `sendFeedback throws an ApiException with the server error message`() = runBlocking {
        server.enqueue(MockResponse(code = 400, body = """{"error":"text fehlt"}"""))

        try {
            api.sendFeedback(server.hostName, server.port, "")
            throw AssertionError("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(400, error.httpCode)
            assertEquals("text fehlt", error.message)
        }
    }
}
