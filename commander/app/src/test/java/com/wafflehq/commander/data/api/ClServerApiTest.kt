package com.wafflehq.commander.data.api

import com.wafflehq.commander.data.connection.AuthSession
import com.wafflehq.commander.data.connection.Connection
import com.wafflehq.commander.data.connection.ConnectionSource
import com.wafflehq.commander.data.connection.Session
import com.wafflehq.commander.data.connection.SessionInvalidator
import java.io.File
import java.time.Instant
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

private const val FAKE_TOKEN = "fake.jwt.token"

private class FakeConnectionSource(session: Session?) : ConnectionSource {
    override val session = MutableStateFlow(session)
}

private class FakeSessionInvalidator : SessionInvalidator {
    var clearCount = 0
        private set

    override suspend fun clearAuthSession() {
        clearCount++
    }
}

class ClServerApiTest {

    private lateinit var server: MockWebServer
    private lateinit var invalidator: FakeSessionInvalidator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        invalidator = FakeSessionInvalidator()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun apiWithConnection(): ClServerApi = ClServerApi(
        FakeConnectionSource(
            Session(
                Connection(server.hostName, server.port),
                AuthSession(FAKE_TOKEN, Instant.now().plusSeconds(3600)),
            ),
        ),
        invalidator,
    )

    private fun apiWithExpiredSession(): ClServerApi = ClServerApi(
        FakeConnectionSource(
            Session(
                Connection(server.hostName, server.port),
                AuthSession(FAKE_TOKEN, Instant.now().minusSeconds(1)),
            ),
        ),
        invalidator,
    )

    private fun apiWithoutConnection(): ClServerApi = ClServerApi(FakeConnectionSource(null), invalidator)

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
    fun `probeStatus returns true on 204`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        val result = apiWithoutConnection().probeStatus(server.hostName, server.port)

        assertTrue(result)
        assertEquals("/status", server.takeRequest().target)
    }

    @Test
    fun `probeStatus returns false on any other status code`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "{}"))

        val result = apiWithoutConnection().probeStatus(server.hostName, server.port)

        assertFalse(result)
    }

    @Test
    fun `probeStatus returns false instead of throwing on network failure`() = runBlocking {
        val host = server.hostName
        val port = server.port
        server.close()

        val result = apiWithoutConnection().probeStatus(host, port)

        assertFalse(result)
    }

    @Test
    fun `login parses the returned token and expiry`() = runBlocking {
        server.enqueue(MockResponse(body = """{"token":"abc.def.ghi","expiresAt":"2030-01-01T00:00:00.000Z"}"""))

        val result = apiWithoutConnection().login(server.hostName, server.port, "123456")

        assertEquals("abc.def.ghi", result.token)
        assertEquals("2030-01-01T00:00:00.000Z", result.expiresAt)
        val recorded = server.takeRequest()
        assertEquals("/auth/login", recorded.target)
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"code\":\"123456\""))
    }

    @Test
    fun `confirmAuthSetup parses the returned token and expiry`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"message":"Google Authenticator aktiviert.","token":"abc.def.ghi","expiresAt":"2030-01-01T00:00:00.000Z"}""",
            ),
        )

        val result = apiWithoutConnection().confirmAuthSetup(server.hostName, server.port, "123456")

        assertEquals("abc.def.ghi", result.token)
        val recorded = server.takeRequest()
        assertEquals("/auth/setup/confirm", recorded.target)
    }

    @Test
    fun `authenticated requests send a valid Authorization Bearer header`() = runBlocking {
        server.enqueue(MockResponse(body = """{"agents":[],"paths":[]}"""))

        apiWithConnection().getManifest()

        val recorded = server.takeRequest()
        assertEquals("Bearer $FAKE_TOKEN", recorded.headers["Authorization"])
    }

    @Test
    fun `authedGet throws AuthRequiredException when no auth session is present`() = runBlocking {
        val api = ClServerApi(
            FakeConnectionSource(Session(Connection(server.hostName, server.port), auth = null)),
            invalidator,
        )
        try {
            api.getManifest()
            fail("expected AuthRequiredException")
        } catch (error: AuthRequiredException) {
            // expected
        }
    }

    @Test
    fun `authedGet throws AuthRequiredException when the stored token is expired`() = runBlocking {
        try {
            apiWithExpiredSession().getManifest()
            fail("expected AuthRequiredException")
        } catch (error: AuthRequiredException) {
            // expected
        }
    }

    @Test
    fun `a 401 response clears the auth session`() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = """{"error":"JWT ungueltig."}"""))

        try {
            apiWithConnection().getManifest()
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(401, error.httpCode)
        }
        assertEquals(1, invalidator.clearCount)
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
