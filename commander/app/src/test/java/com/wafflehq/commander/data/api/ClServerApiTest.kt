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
    fun `listTickets without status requests the plain path and parses the ticket list`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"tickets":[{"id":1,"pathName":"myapp","title":"t","description":"d","task":"x","status":"open","createdAt":"c","updatedAt":"u"}]}""",
            ),
        )

        val result = apiWithConnection().listTickets("myapp")

        assertEquals(1, result.tickets.size)
        assertEquals("open", result.tickets.first().status)
        val recorded = server.takeRequest()
        assertEquals("/tickets/myapp", recorded.target)
    }

    @Test
    fun `listTickets with status appends the status query parameter`() = runBlocking {
        server.enqueue(MockResponse(body = """{"tickets":[]}"""))

        apiWithConnection().listTickets("myapp", status = "open")

        val recorded = server.takeRequest()
        assertEquals("/tickets/myapp?status=open", recorded.target)
    }

    @Test
    fun `createTicket posts the text and parses the created ticket`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 201,
                body = """{"id":1,"pathName":"myapp","title":"t","description":"d","task":"x","status":"open","createdAt":"c","updatedAt":"u"}""",
            ),
        )

        val result = apiWithConnection().createTicket("myapp", "ein neues Feature")

        assertEquals(1, result.id)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/tickets/myapp", recorded.target)
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"text\":\"ein neues Feature\""))
    }

    @Test
    fun `getTicket requests the ticket by id`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"id":42,"pathName":"myapp","title":"t","description":"d","task":"x","status":"open","createdAt":"c","updatedAt":"u"}""",
            ),
        )

        val result = apiWithConnection().getTicket("myapp", 42)

        assertEquals(42, result.id)
        val recorded = server.takeRequest()
        assertEquals("/tickets/myapp/42", recorded.target)
    }

    @Test
    fun `updateTicket sends a PATCH request with only the provided fields`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"id":1,"pathName":"myapp","title":"Neu","description":"d","task":"x","status":"closed","createdAt":"c","updatedAt":"u"}""",
            ),
        )

        val result = apiWithConnection().updateTicket(
            "myapp",
            1,
            TicketPatchRequest(title = "Neu", status = TICKET_STATUS_CLOSED),
        )

        assertEquals("Neu", result.title)
        assertEquals(TICKET_STATUS_CLOSED, result.status)
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/tickets/myapp/1", recorded.target)
        val body = recorded.body?.utf8().orEmpty()
        assertTrue(body.contains("\"title\":\"Neu\""))
        assertTrue(body.contains("\"status\":\"closed\""))
        assertFalse(body.contains("description"))
    }

    @Test
    fun `deleteTicket sends a DELETE request`() = runBlocking {
        server.enqueue(MockResponse(body = """{"message":"Ticket \"1\" wurde geloescht."}"""))

        val result = apiWithConnection().deleteTicket("myapp", 1)

        assertTrue(result.message.isNotEmpty())
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/tickets/myapp/1", recorded.target)
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
