package com.wafflehq.commander.data.api

import com.wafflehq.commander.data.connection.Connection
import com.wafflehq.commander.data.connection.ConnectionSource
import com.wafflehq.commander.data.totp.TotpGenerator
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

private class FakeConnectionSource(connection: Connection?) : ConnectionSource {
    override val connection = MutableStateFlow(connection)
}

class ClServerApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun apiWithConnection(): ClServerApi =
        ClServerApi(FakeConnectionSource(Connection(server.hostName, server.port, SECRET)))

    private fun apiWithoutConnection(): ClServerApi =
        ClServerApi(FakeConnectionSource(null))

    @Test
    fun `health parses a successful response`() = runBlocking {
        server.enqueue(MockResponse(body = """{"status":"ok","version":"0.1.0"}"""))

        val result = apiWithoutConnection().health(server.hostName, server.port)

        assertEquals(HealthResponse("ok", "0.1.0"), result)
    }

    @Test
    fun `error response maps the server error message and http code`() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = """{"error":"Route nicht gefunden."}"""))

        try {
            apiWithoutConnection().health(server.hostName, server.port)
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(404, error.httpCode)
            assertEquals("Route nicht gefunden.", error.message)
        }
    }

    @Test
    fun `network failure is wrapped as ApiException with null http code`() = runBlocking {
        val host = server.hostName
        val port = server.port
        server.close()

        try {
            apiWithoutConnection().health(host, port)
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertNull(error.httpCode)
        }
    }

    @Test
    fun `verifySecret returns false on 401 without throwing`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"error":"nope"}"""))

        val valid = apiWithoutConnection().verifySecret(server.hostName, server.port, SECRET)

        assertFalse(valid)
    }

    @Test
    fun `verifySecret returns true on 200`() = runBlocking {
        server.enqueue(MockResponse(body = """{"paths":["myapp"]}"""))

        val valid = apiWithoutConnection().verifySecret(server.hostName, server.port, SECRET)

        assertTrue(valid)
    }

    @Test
    fun `verifySecret rethrows non-401 errors`() = runBlocking {
        server.enqueue(MockResponse(code = 500, body = """{"error":"boom"}"""))

        try {
            apiWithoutConnection().verifySecret(server.hostName, server.port, SECRET)
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(500, error.httpCode)
        }
    }

    @Test
    fun `authenticated requests send a valid X-TOTP-Code header`() = runBlocking {
        server.enqueue(MockResponse(body = """{"agents":[],"paths":[]}"""))

        apiWithConnection().getManifest()

        val recorded = server.takeRequest()
        val code = recorded.headers["X-TOTP-Code"]
        assertEquals(TotpGenerator.generate(SECRET), code)
    }

    @Test
    fun `runAgent with no agent name posts to the root path`() = runBlocking {
        server.enqueue(MockResponse(code = 202, body = """{"id":"abc-123"}"""))

        val result = apiWithConnection().runAgent(agentName = null, path = "myapp", command = "do it", model = null)

        assertEquals("abc-123", result.id)
        val recorded = server.takeRequest()
        val body = recorded.body?.utf8().orEmpty()
        assertEquals("/", recorded.target)
        assertTrue(body.contains("\"command\":\"do it\""))
        assertFalse(body.contains("model"))
    }

    @Test
    fun `runAgent with an agent name posts to its named path`() = runBlocking {
        server.enqueue(MockResponse(code = 202, body = """{"id":"abc-123"}"""))

        apiWithConnection().runAgent(agentName = "dev", path = "myapp", command = "do it", model = "opus")

        val recorded = server.takeRequest()
        assertEquals("/dev", recorded.target)
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"model\":\"opus\""))
    }

    @Test
    fun `downloadHostedEntry writes the response body using the Content-Disposition filename`(): Unit = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = okhttp3.Headers.headersOf("Content-Disposition", "attachment; filename=\"app-debug.apk\""),
                body = "apk-bytes",
            ),
        )
        val dir = File.createTempFile("commander-test", "").apply { delete(); mkdirs() }

        val file = apiWithConnection().downloadHostedEntry("periodical", "debug-apk", dir)

        assertEquals("app-debug.apk", file.name)
        assertEquals("apk-bytes", file.readText())
    }
}
