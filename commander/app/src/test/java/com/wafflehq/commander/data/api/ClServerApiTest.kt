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
import mockwebserver3.SocketEffect
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
    fun `stopCommand posts to state slash id slash stop`() = runBlocking {
        server.enqueue(MockResponse(code = 202, body = """{"id":"abc-123"}"""))

        val result = apiWithConnection().stopCommand("abc-123")

        assertEquals("abc-123", result.id)
        val recorded = server.takeRequest()
        assertEquals("/state/abc-123/stop", recorded.target)
        assertEquals("POST", recorded.method)
    }

    @Test
    fun `getCommands requests the path-scoped history and parses newest-first`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"commands":[{"id":"2","agent":"main","model":"sonnet","command":"b","path":"/p","status":"completed","output":"","exitCode":0,"createdAt":"2","updatedAt":"2"},{"id":"1","agent":"main","model":"sonnet","command":"a","path":"/p","status":"completed","output":"","exitCode":0,"createdAt":"1","updatedAt":"1"}],"total":2,"limit":5,"offset":0,"hasMore":false}""",
            ),
        )

        val result = apiWithConnection().getCommands("myapp")

        assertEquals(listOf("2", "1"), result.commands.map { it.id })
        assertEquals(2, result.total)
        assertEquals(5, result.limit)
        assertEquals(0, result.offset)
        assertEquals(false, result.hasMore)
        val recorded = server.takeRequest()
        assertEquals("/commands/myapp", recorded.target)
    }

    @Test
    fun `getCommands appends limit and offset query parameters when given`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"commands":[],"total":12,"limit":5,"offset":5,"hasMore":true}"""),
        )

        val result = apiWithConnection().getCommands("myapp", limit = 5, offset = 5)

        assertEquals(12, result.total)
        assertEquals(true, result.hasMore)
        val recorded = server.takeRequest()
        assertEquals("/commands/myapp?limit=5&offset=5", recorded.target)
    }

    @Test
    fun `getUsage requests the plain path and parses the limits`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"limits":[{"label":"Current session","percentUsed":42,"resetsAt":"Aug 27, 5:40pm (Europe/Berlin)"}]}""",
            ),
        )

        val result = apiWithConnection().getUsage()

        assertEquals(1, result.size)
        assertEquals("Current session", result[0].label)
        assertEquals(42, result[0].percentUsed)
        assertEquals("Aug 27, 5:40pm (Europe/Berlin)", result[0].resetsAt)
        val recorded = server.takeRequest()
        assertEquals("/usage", recorded.target)
    }

    @Test
    fun `getCosts requests the plain path and parses the overview`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"totalCostUsd":12.34,"totalInputTokens":1000,"totalOutputTokens":2000,"totalCacheCreationInputTokens":500,"totalCacheReadInputTokens":300,"projects":[{"pathName":"myapp","totalCostUsd":3.21,"entries":[{"id":"abc","createdAt":"2026-09-14T12:00:00.000Z","costUsd":0.02,"inputTokens":10,"outputTokens":40,"cacheCreationInputTokens":0,"cacheReadInputTokens":100}]}]}""",
            ),
        )

        val result = apiWithConnection().getCosts()

        assertEquals(12.34, result.totalCostUsd, 0.0)
        assertEquals(1000L, result.totalInputTokens)
        assertEquals(1, result.projects.size)
        assertEquals("myapp", result.projects.first().pathName)
        assertEquals(1, result.projects.first().entries.size)
        assertEquals("abc", result.projects.first().entries.first().id)
        val recorded = server.takeRequest()
        assertEquals("/costs", recorded.target)
    }

    @Test
    fun `getSystemMetrics requests the plain path and parses the samples`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"metrics":[{"createdAt":"2026-09-14T12:00:00.000Z","cpuPercent":23.4,"memUsedPercent":61.2,"memTotalBytes":16000000000,"memFreeBytes":6200000000}],"windowHours":24}""",
            ),
        )

        val result = apiWithConnection().getSystemMetrics()

        assertEquals(1, result.metrics.size)
        assertEquals(23.4, result.metrics.first().cpuPercent, 0.0)
        assertEquals(16_000_000_000L, result.metrics.first().memTotalBytes)
        assertEquals(24.0, result.windowHours, 0.0)
        val recorded = server.takeRequest()
        assertEquals("/system-metrics", recorded.target)
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
    fun `streamState wraps a mid-stream socket close as ApiException instead of a raw IOException`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .onResponseStart(SocketEffect.CloseSocket())
                .build(),
        )

        try {
            apiWithConnection().streamState("1").toList()
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(null, error.httpCode)
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

    @Test
    fun `getAllSessions requests the global endpoint and parses the activity`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"sessions":[{"pid":123,"cwd":"/a","kind":"interactive","startedAt":1700000000000,"sessionId":"s1","name":"a","state":"working","activity":"working"},{"pid":456,"id":"1771997d","cwd":"/b","kind":"background","startedAt":1700000100000,"sessionId":"s2","name":"b","waitingFor":"permission","activity":"waiting"},{"cwd":"/c","kind":"interactive","startedAt":1700000200000,"sessionId":"s3","name":"c","activity":"idle"},{"cwd":"/d","kind":"interactive","startedAt":1700000300000,"sessionId":"s4","name":"d","activity":"something-new"}]}""",
            ),
        )

        val result = apiWithConnection().getAllSessions()

        assertEquals(
            listOf(SessionActivity.Working, SessionActivity.Waiting, SessionActivity.Idle, SessionActivity.Idle),
            result.map { it.sessionActivity() },
        )
        assertFalse(result[0].isBackground())
        assertTrue(result[1].isBackground())
        assertEquals("permission", result[1].waitingFor)
        assertNull(result[2].pid)
        assertEquals("/remote-sessions", server.takeRequest().target)
    }

    @Test
    fun `killSession posts to remote-sessions slash id slash kill`() = runBlocking {
        server.enqueue(MockResponse(body = """{"killed":true}"""))

        val result = apiWithConnection().killSession("s1")

        assertTrue(result.killed)
        val recorded = server.takeRequest()
        assertEquals("/remote-sessions/s1/kill", recorded.target)
        assertEquals("POST", recorded.method)
    }

    @Test
    fun `killSession surfaces a server error`() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = """{"error":"Keine Session mit der ID \"x\" gefunden."}"""))

        try {
            apiWithConnection().killSession("x")
            fail("expected ApiException")
        } catch (error: ApiException) {
            assertEquals(404, error.httpCode)
        }
    }

    @Test
    fun `getGoals requests the path-scoped endpoint and parses grouped goal lists`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"goalLists":[{"folder":"2026-09-27-feature","planTitle":"Feature","planDate":"2026-09-27","totalCount":3,"goals":[""" +
                    """{"id":"G01","fileName":"G01-erstes.md","title":"Erstes","description":"Beschreibung","date":"2026-09-27",""" +
                    """"dependsOn":[],"command":"/goal Tu etwas.","content":"---\nid: G01\n---\n","timestamp":"2026-04-05T06:07:08.000Z",""" +
                    """"legacy":false,"status":"ready","missingDependencies":[],"running":true},""" +
                    """{"id":"G02","fileName":"G02-zweites.md","title":"Zweites","description":"","date":"2026-09-27",""" +
                    """"dependsOn":["G01"],"command":"/goal Tu etwas anderes.","content":"","timestamp":"2026-04-06T06:07:08.000Z",""" +
                    """"legacy":false,"status":"blocked","missingDependencies":["G01"]}]}]}""",
            ),
        )

        val result = apiWithConnection().getGoals("myapp")

        assertEquals(1, result.size)
        assertEquals("2026-09-27-feature", result[0].folder)
        assertEquals("Feature", result[0].planTitle)
        assertEquals(2, result[0].goals.size)
        assertEquals(3, result[0].totalCount)
        assertTrue(result[0].goals[0].running)
        assertFalse(result[0].goals[1].running)
        assertEquals("G01", result[0].goals[0].id)
        assertTrue(result[0].goals[0].isReady())
        assertEquals(listOf("G01"), result[0].goals[1].dependsOn)
        assertFalse(result[0].goals[1].isReady())
        assertEquals(listOf("G01"), result[0].goals[1].missingDependencies)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/paths/myapp/goals", recorded.target)
    }

    @Test
    fun `getGoals returns an empty list when the project has no goal lists`() = runBlocking {
        server.enqueue(MockResponse(body = """{"goalLists":[]}"""))

        val result = apiWithConnection().getGoals("myapp")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getGoals encodes special characters in the path name`() = runBlocking {
        server.enqueue(MockResponse(body = """{"goalLists":[]}"""))

        apiWithConnection().getGoals("my app")

        assertEquals("/paths/my%20app/goals", server.takeRequest().target)
    }

    @Test
    fun `startGoal posts to the folder- and file-scoped start endpoint`() = runBlocking {
        server.enqueue(MockResponse(code = 202, body = """{"id":"cmd-1"}"""))

        val result = apiWithConnection().startGoal("myapp", "2026-09-27-feature", "G01-erstes.md")

        assertEquals("cmd-1", result.id)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/paths/myapp/goals/2026-09-27-feature/G01-erstes.md/start", recorded.target)
    }

    @Test
    fun `startGoal posts the given model with interactive false`() = runBlocking {
        server.enqueue(MockResponse(code = 202, body = """{"id":"cmd-1"}"""))

        apiWithConnection().startGoal("myapp", "2026-09-27-feature", "G01-erstes.md", "sonnet")

        val body = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(body.contains("\"interactive\":false"))
        assertTrue(body.contains("\"model\":\"sonnet\""))
    }

    @Test
    fun `startGoalInteractive posts interactive true to the start endpoint`() = runBlocking {
        server.enqueue(MockResponse(code = 201, body = """{"id":"abc123f9","output":"backgrounded"}"""))

        val result = apiWithConnection().startGoalInteractive("myapp", "2026-09-27-feature", "G01-erstes.md")

        assertEquals("abc123f9", result.id)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/paths/myapp/goals/2026-09-27-feature/G01-erstes.md/start", recorded.target)
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"interactive\":true"))
    }

    @Test
    fun `startRemoteSession posts without a body field when no name is given`() = runBlocking {
        server.enqueue(MockResponse(code = 201, body = """{"id":"abc123f9","output":"backgrounded"}"""))

        val result = apiWithConnection().startRemoteSession("myapp")

        assertEquals("abc123f9", result.id)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/paths/myapp/remote-sessions", recorded.target)
        assertEquals("{}", recorded.body?.utf8())
    }

    @Test
    fun `startRemoteSession posts the given name`() = runBlocking {
        server.enqueue(MockResponse(code = 201, body = """{"id":"xyz98765","output":"backgrounded"}"""))

        apiWithConnection().startRemoteSession("myapp", name = "mein-name")

        val recorded = server.takeRequest()
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"name\":\"mein-name\""))
    }

    @Test
    fun `startRemoteSession posts the given model`() = runBlocking {
        server.enqueue(MockResponse(code = 201, body = """{"id":"xyz98765","output":"backgrounded"}"""))

        apiWithConnection().startRemoteSession("myapp", model = "sonnet")

        val recorded = server.takeRequest()
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"model\":\"sonnet\""))
    }

    @Test
    fun `startGoalInteractive posts the given model`() = runBlocking {
        server.enqueue(MockResponse(code = 201, body = """{"id":"abc123f9","output":"backgrounded"}"""))

        apiWithConnection().startGoalInteractive("myapp", "2026-09-27-feature", "G01-erstes.md", "opus")

        val body = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(body.contains("\"interactive\":true"))
        assertTrue(body.contains("\"model\":\"opus\""))
    }

    @Test
    fun `getPathSchedulers requests the path-scoped endpoint and parses schedulers and script schedulers`() = runBlocking {
        server.enqueue(
            MockResponse(
                body = """{"schedulers":[{"name":"nightly-sync","description":"Sync","cron":"0 0 3 * * *","paths":["myapp"]}],"scriptSchedulers":[{"name":"auto-commit","description":"Auto-Commit","cron":"0 * * * *","paths":["myapp"],"script":"echo hi"}]}""",
            ),
        )

        val result = apiWithConnection().getPathSchedulers("myapp")

        assertEquals(1, result.schedulers.size)
        assertEquals("nightly-sync", result.schedulers[0].name)
        assertEquals(1, result.scriptSchedulers.size)
        assertEquals("auto-commit", result.scriptSchedulers[0].name)
        val recorded = server.takeRequest()
        assertEquals("/paths/myapp/schedulers", recorded.target)
    }

    @Test
    fun `getAllSchedulers requests the project-independent endpoint and parses instructions and path statuses`() =
        runBlocking {
            server.enqueue(
                MockResponse(
                    body = """{"schedulers":[{"name":"nightly-sync","description":"Sync","cron":"0 0 3 * * *","paths":["myapp","other"],"instructions":"# Context\n","pathStatuses":[{"pathName":"myapp","enabled":true},{"pathName":"other","enabled":false}]}]}""",
                ),
            )

            val result = apiWithConnection().getAllSchedulers()

            assertEquals(1, result.schedulers.size)
            val scheduler = result.schedulers[0]
            assertEquals("nightly-sync", scheduler.name)
            assertEquals("# Context\n", scheduler.instructions)
            assertEquals(listOf("myapp", "other"), scheduler.paths)
            assertEquals(false, scheduler.pathStatuses.single { it.pathName == "other" }.enabled)
            val recorded = server.takeRequest()
            assertEquals("/schedulers", recorded.target)
        }

    @Test
    fun `getAllScriptSchedulers requests the project-independent endpoint and parses the script and path statuses`() =
        runBlocking {
            server.enqueue(
                MockResponse(
                    body = """{"scriptSchedulers":[{"name":"auto-commit","description":"Auto-Commit","cron":"0 * * * *","paths":["myapp"],"script":"echo hi","pathStatuses":[{"pathName":"myapp","enabled":true}]}]}""",
                ),
            )

            val result = apiWithConnection().getAllScriptSchedulers()

            assertEquals(1, result.scriptSchedulers.size)
            val scheduler = result.scriptSchedulers[0]
            assertEquals("echo hi", scheduler.script)
            assertEquals(true, scheduler.pathStatuses.single().enabled)
            val recorded = server.takeRequest()
            assertEquals("/script-schedulers", recorded.target)
        }

    @Test
    fun `triggerScheduler posts to the path-scoped trigger endpoint`() = runBlocking {
        server.enqueue(MockResponse(code = 202, body = """{"id":"abc-123"}"""))

        val result = apiWithConnection().triggerScheduler("myapp", "nightly-sync")

        assertEquals("abc-123", result.id)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/paths/myapp/schedulers/nightly-sync/trigger", recorded.target)
    }

    @Test
    fun `triggerScriptScheduler posts to the path-scoped trigger endpoint`() = runBlocking {
        server.enqueue(MockResponse(code = 202, body = """{"id":"abc-456"}"""))

        val result = apiWithConnection().triggerScriptScheduler("myapp", "auto-commit")

        assertEquals("abc-456", result.id)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/paths/myapp/script-schedulers/auto-commit/trigger", recorded.target)
    }
}
