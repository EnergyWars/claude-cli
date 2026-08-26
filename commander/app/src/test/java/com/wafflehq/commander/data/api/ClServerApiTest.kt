package com.wafflehq.commander.data.api

import com.wafflehq.commander.data.connection.AuthSession
import com.wafflehq.commander.data.connection.Connection
import com.wafflehq.commander.data.connection.ConnectionSource
import com.wafflehq.commander.data.connection.Session
import com.wafflehq.commander.data.connection.SessionWriter
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
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

private class FakeSessionWriter : SessionWriter {
    var clearCount = 0
        private set
    var savedToken: String? = null
        private set
    var savedExpiresAt: Instant? = null
        private set

    override suspend fun saveAuthSession(token: String, expiresAt: Instant) {
        savedToken = token
        savedExpiresAt = expiresAt
    }

    override suspend fun clearAuthSession() {
        clearCount++
    }
}

class ClServerApiTest {

    private lateinit var server: MockWebServer
    private lateinit var invalidator: FakeSessionWriter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        invalidator = FakeSessionWriter()
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
    fun `getCommands requests the path-scoped history and parses newest-first`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"commands":[{"id":"2","agent":"main","model":"sonnet","command":"b","path":"/p","status":"completed","output":"","exitCode":0,"createdAt":"2","updatedAt":"2"},{"id":"1","agent":"main","model":"sonnet","command":"a","path":"/p","status":"completed","output":"","exitCode":0,"createdAt":"1","updatedAt":"1"}]}""",
            ),
        )

        val result = apiWithConnection().getCommands("myapp")

        assertEquals(listOf("2", "1"), result.commands.map { it.id })
        val recorded = server.takeRequest()
        assertEquals("/commands/myapp", recorded.target)
    }

    @Test
    fun `listTickets without status requests the plain path and parses the ticket list`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"tickets":[{"id":1,"pathName":"myapp","originalRequest":"r","summary":"s","claudeInstruction":"i","category":"c","status":"open","createdAt":"c","updatedAt":"u"}]}""",
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
    fun `listAllTickets requests the global tickets path`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"tickets":[{"id":1,"pathName":"myapp","originalRequest":"r","summary":"s","claudeInstruction":"i","category":"c","status":"open","createdAt":"c","updatedAt":"u"}]}""",
            ),
        )

        val result = apiWithConnection().listAllTickets()

        assertEquals(1, result.tickets.size)
        val recorded = server.takeRequest()
        assertEquals("/tickets", recorded.target)
    }

    @Test
    fun `listAllTickets with status appends the status query parameter`() = runBlocking {
        server.enqueue(MockResponse(body = """{"tickets":[]}"""))

        apiWithConnection().listAllTickets(status = "rejected")

        val recorded = server.takeRequest()
        assertEquals("/tickets?status=rejected", recorded.target)
    }

    @Test
    fun `createTicket posts the text and parses the created ticket`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 201,
                body = """{"id":1,"pathName":"myapp","originalRequest":"ein neues Feature","summary":"","claudeInstruction":"","category":"","status":"generating","ipAddress":"192.168.1.5","createdAt":"c","updatedAt":"u"}""",
            ),
        )

        val result = apiWithConnection().createTicket("myapp", "ein neues Feature")

        assertEquals(1, result.id)
        assertEquals("192.168.1.5", result.ipAddress)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/tickets/myapp", recorded.target)
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"text\":\"ein neues Feature\""))
    }

    @Test
    fun `getTicket requests the ticket by id`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"id":42,"pathName":"myapp","originalRequest":"r","summary":"s","claudeInstruction":"i","category":"c","status":"open","createdAt":"c","updatedAt":"u"}""",
            ),
        )

        val result = apiWithConnection().getTicket("myapp", 42)

        assertEquals(42, result.id)
        assertEquals(null, result.ipAddress)
        val recorded = server.takeRequest()
        assertEquals("/tickets/myapp/42", recorded.target)
    }

    @Test
    fun `updateTicket sends a PATCH request with only the provided fields`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"id":1,"pathName":"myapp","originalRequest":"r","summary":"Neu","claudeInstruction":"i","category":"c","status":"done","createdAt":"c","updatedAt":"u"}""",
            ),
        )

        val result = apiWithConnection().updateTicket(
            "myapp",
            1,
            TicketPatchRequest(summary = "Neu", status = TICKET_STATUS_DONE),
        )

        assertEquals("Neu", result.summary)
        assertEquals(TICKET_STATUS_DONE, result.status)
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/tickets/myapp/1", recorded.target)
        val body = recorded.body?.utf8().orEmpty()
        assertTrue(body.contains("\"summary\":\"Neu\""))
        assertTrue(body.contains("\"status\":\"done\""))
        assertFalse(body.contains("claudeInstruction"))
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
    fun `collect posts an empty body without a targetName`() = runBlocking {
        server.enqueue(MockResponse(body = """{"results":[],"errors":[]}"""))

        val result = apiWithConnection().collect()

        assertTrue(result.results.isEmpty())
        assertTrue(result.errors.isEmpty())
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/collect", recorded.target)
        assertFalse(recorded.body?.utf8().orEmpty().contains("targetName"))
    }

    @Test
    fun `collect sends the targetName when given`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"results":[{"targetName":"test","fileName":"test.apk","status":"ok"}],"errors":[]}"""),
        )

        val result = apiWithConnection().collect("test")

        assertEquals(1, result.results.size)
        val recorded = server.takeRequest()
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"targetName\":\"test\""))
    }

    @Test
    fun `getFeedback lists feedback entries`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"feedback":[{"id":1,"text":"Bitte Dark Mode.","createdAt":"c","updatedAt":"c"}]}"""),
        )

        val result = apiWithConnection().getFeedback()

        assertEquals(1, result.feedback.size)
        assertEquals("Bitte Dark Mode.", result.feedback.first().text)
        assertEquals(null, result.feedback.first().section)
        val recorded = server.takeRequest()
        assertEquals("/feedback", recorded.target)
    }

    @Test
    fun `getFeedback exposes the section an entry was sent from`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"feedback":[{"id":1,"text":"Absturz","section":"periodical-debug","createdAt":"c","updatedAt":"c"}]}""",
            ),
        )

        val result = apiWithConnection().getFeedback()

        assertEquals("periodical-debug", result.feedback.first().section)
    }

    @Test
    fun `getFeedback exposes the context of an entry`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"feedback":[{"id":1,"text":"Absturz","section":"periodical-debug","context":"periodical-debug.apk (2026-08-26T10:00:00.000Z)","createdAt":"c","updatedAt":"c"}]}""",
            ),
        )

        val result = apiWithConnection().getFeedback()

        assertEquals("periodical-debug.apk (2026-08-26T10:00:00.000Z)", result.feedback.first().context)
    }

    @Test
    fun `updateFeedback sends a PATCH request with the new text`() = runBlocking {
        server.enqueue(MockResponse(body = """{"id":1,"text":"Neu","createdAt":"c","updatedAt":"u"}"""))

        val result = apiWithConnection().updateFeedback(1, "Neu")

        assertEquals("Neu", result.text)
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/feedback/1", recorded.target)
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"text\":\"Neu\""))
    }

    @Test
    fun `deleteFeedback sends a DELETE request`() = runBlocking {
        server.enqueue(MockResponse(body = """{"message":"Feedback \"1\" wurde geloescht."}"""))

        val result = apiWithConnection().deleteFeedback(1)

        assertTrue(result.message.isNotEmpty())
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/feedback/1", recorded.target)
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

    @Test
    fun `downloadHostedEntry reports the final progress with the known total size`(): Unit = runBlocking {
        val body = "x".repeat(1_000)
        server.enqueue(MockResponse(code = 200, body = body))
        val dir = File.createTempFile("commander-test", "").apply { delete(); mkdirs() }
        val progressUpdates = mutableListOf<DownloadProgress>()

        apiWithConnection().downloadHostedEntry("periodical", "debug-apk", dir, onProgress = { progressUpdates.add(it) })

        val last = progressUpdates.last()
        assertEquals(1_000L, last.bytesDownloaded)
        assertEquals(1_000L, last.totalBytes)
    }

    @Test
    fun `streamState emits one CommandState per data event`() = runBlocking {
        val body = "data: {\"id\":\"1\",\"agent\":\"main\",\"model\":\"sonnet\",\"command\":\"x\",\"path\":\"/p\"," +
            "\"status\":\"running\",\"output\":\"a\",\"exitCode\":null,\"createdAt\":\"c\",\"updatedAt\":\"u1\"}\n\n" +
            "data: {\"id\":\"1\",\"agent\":\"main\",\"model\":\"sonnet\",\"command\":\"x\",\"path\":\"/p\"," +
            "\"status\":\"completed\",\"output\":\"ab\",\"exitCode\":0,\"createdAt\":\"c\",\"updatedAt\":\"u2\"}\n\n"
        server.enqueue(
            MockResponse(body = body, headers = okhttp3.Headers.headersOf("Content-Type", "text/event-stream")),
        )

        val events = apiWithConnection().streamState("1").toList()

        assertEquals(2, events.size)
        assertEquals("running", events[0].status)
        assertEquals("completed", events[1].status)
        assertEquals(0, events[1].exitCode)
        val recorded = server.takeRequest()
        assertEquals("/state/1/stream", recorded.target)
    }

    @Test
    fun `streamState ignores heartbeat comment lines`() = runBlocking {
        val body = ": heartbeat\n\n" +
            "data: {\"id\":\"1\",\"agent\":\"main\",\"model\":\"sonnet\",\"command\":\"x\",\"path\":\"/p\"," +
            "\"status\":\"completed\",\"output\":\"a\",\"exitCode\":0,\"createdAt\":\"c\",\"updatedAt\":\"u\"}\n\n"
        server.enqueue(MockResponse(body = body))

        val events = apiWithConnection().streamState("1").toList()

        assertEquals(1, events.size)
        assertEquals("completed", events.first().status)
    }

    @Test
    fun `streamState throws ApiException on an error response`() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = """{"error":"Command \"1\" wurde nicht gefunden."}"""))

        try {
            apiWithConnection().streamState("1").toList()
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(404, error.httpCode)
        }
    }

    @Test
    fun `refreshSessionIfLoggedIn posts to auth refresh and saves the new token`() = runBlocking {
        server.enqueue(MockResponse(body = """{"token":"new.jwt.token","expiresAt":"2030-01-01T00:00:00.000Z"}"""))

        apiWithConnection().refreshSessionIfLoggedIn()

        val recorded = server.takeRequest()
        assertEquals("/auth/refresh", recorded.target)
        assertEquals("Bearer $FAKE_TOKEN", recorded.headers["Authorization"])
        assertEquals("new.jwt.token", invalidator.savedToken)
    }

    @Test
    fun `refreshSessionIfLoggedIn does nothing without a saved connection`() = runBlocking {
        apiWithoutConnection().refreshSessionIfLoggedIn()

        assertEquals(0, server.requestCount)
        assertNull(invalidator.savedToken)
    }

    @Test
    fun `refreshSessionIfLoggedIn does nothing when the token is already expired`() = runBlocking {
        apiWithExpiredSession().refreshSessionIfLoggedIn()

        assertEquals(0, server.requestCount)
        assertNull(invalidator.savedToken)
    }

    @Test
    fun `a successful authenticated call schedules a background token refresh`() = runBlocking {
        server.enqueue(MockResponse(body = """{"agents":[],"paths":[]}"""))
        server.enqueue(MockResponse(body = """{"token":"new.jwt.token","expiresAt":"2030-01-01T00:00:00.000Z"}"""))

        apiWithConnection().getManifest()

        assertEquals("/manifest", server.takeRequest().target)
        val refreshRequest = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/auth/refresh", refreshRequest?.target)
    }

    @Test
    fun `unauthenticated calls do not schedule a background token refresh`() = runBlocking {
        server.enqueue(MockResponse(body = """{"status":"ok","version":"0.1.0"}"""))

        apiWithoutConnection().health(server.hostName, server.port)

        assertEquals("/health", server.takeRequest().target)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `background refresh is debounced within the minimum interval`() = runBlocking {
        server.enqueue(MockResponse(body = """{"agents":[],"paths":[]}"""))
        server.enqueue(MockResponse(body = """{"token":"new.jwt.token","expiresAt":"2030-01-01T00:00:00.000Z"}"""))
        server.enqueue(MockResponse(body = """{"agents":[],"paths":[]}"""))

        val api = apiWithConnection()
        api.getManifest()
        server.takeRequest()
        server.takeRequest(2, TimeUnit.SECONDS)

        api.getManifest()
        server.takeRequest()

        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }
}
