package com.wafflehq.commander.data.api

import com.wafflehq.commander.data.connection.ConnectionSource
import com.wafflehq.commander.data.connection.Session
import com.wafflehq.commander.data.connection.SessionWriter
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.buffer
import okio.sink

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val AUTHORIZATION_HEADER = "Authorization"
private val CONTENT_DISPOSITION_FILENAME = Regex("filename=\"([^\"]+)\"")
private const val DOWNLOAD_CHUNK_SIZE = 8_192L
private const val PROGRESS_EMIT_INTERVAL_NANOS = 150_000_000L
private const val SSE_DATA_PREFIX = "data: "
private const val AUTH_REFRESH_PATH = "/auth/refresh"
private val MIN_AUTO_REFRESH_INTERVAL: Duration = Duration.ofMinutes(1)

@Singleton
class ClServerApi @Inject constructor(
    private val connectionSource: ConnectionSource,
    private val sessionInvalidator: SessionWriter,
) {
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastRefreshAttemptAt: Instant = Instant.EPOCH
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Short timeouts for scanning many local-network addresses at once during auto-discovery. */
    private val discoveryClient = client.newBuilder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(400, TimeUnit.MILLISECONDS)
        .build()

    /** GET /state/:id/stream (Server-Sent Events) stays open until the command finishes - no read timeout. */
    private val streamingClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun urlBuilder(host: String, port: Int): HttpUrl.Builder =
        HttpUrl.Builder().scheme("http").host(host).port(port)

    private suspend fun requireSession(): Session {
        val session = connectionSource.session.first()
            ?: throw ApiException(null, "Keine Verbindung konfiguriert.")
        val auth = session.auth
        if (auth == null || !auth.expiresAt.isAfter(Instant.now())) {
            throw AuthRequiredException("Nicht eingeloggt.")
        }
        return session
    }

    /** Called on app foreground (see ConnectionGateViewModel) to keep the session alive independent of user actions. */
    suspend fun refreshSessionIfLoggedIn() {
        val session = connectionSource.session.first() ?: return
        val auth = session.auth ?: return
        if (!auth.expiresAt.isAfter(Instant.now())) return
        performRefresh(session)
    }

    /** Best-effort sliding-session refresh triggered after every successful authenticated call. */
    private fun scheduleBackgroundRefresh() {
        val now = Instant.now()
        if (Duration.between(lastRefreshAttemptAt, now) < MIN_AUTO_REFRESH_INTERVAL) return
        lastRefreshAttemptAt = now
        refreshScope.launch {
            val session = connectionSource.session.first() ?: return@launch
            performRefresh(session)
        }
    }

    private suspend fun performRefresh(session: Session) {
        val auth = session.auth ?: return
        try {
            val request = Request.Builder()
                .url(urlBuilder(session.connection.host, session.connection.port).addPathSegments("auth/refresh").build())
                .header(AUTHORIZATION_HEADER, "Bearer ${auth.token}")
                .post("".toRequestBody(JSON_MEDIA_TYPE))
            val response: AuthTokenResponse = execute(request)
            sessionInvalidator.saveAuthSession(response.token, Instant.parse(response.expiresAt))
        } catch (error: ApiException) {
            // Best effort - der naechste erfolgreiche Call versucht es erneut.
        }
    }

    // -- Unauthenticated / pairing calls (host+port passed explicitly, no saved Connection yet) --

    suspend fun health(host: String, port: Int): HealthResponse =
        execute(Request.Builder().url(urlBuilder(host, port).addPathSegment("health").build()).get())

    suspend fun authStatus(host: String, port: Int): AuthStatusResponse =
        execute(Request.Builder().url(urlBuilder(host, port).addPathSegments("auth/status").build()).get())

    /** GET /status: 204 without a body, used to probe candidate addresses during auto-discovery. */
    suspend fun probeStatus(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(urlBuilder(host, port).addPathSegment("status").build()).get().build()
        try {
            discoveryClient.newCall(request).execute().use { it.code == 204 }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
    }

    suspend fun confirmAuthSetup(host: String, port: Int, code: String): AuthTokenResponse =
        execute(
            Request.Builder()
                .url(urlBuilder(host, port).addPathSegments("auth/setup/confirm").build())
                .post(json.encodeToString(AuthCodeRequest(code)).toRequestBody(JSON_MEDIA_TYPE))
        )

    suspend fun login(host: String, port: Int, code: String): AuthTokenResponse =
        execute(
            Request.Builder()
                .url(urlBuilder(host, port).addPathSegments("auth/login").build())
                .post(json.encodeToString(AuthCodeRequest(code)).toRequestBody(JSON_MEDIA_TYPE))
        )

    // -- Authenticated calls (use the saved Session) --

    // Kein getPaths()/getPathCommands(): GET /manifest liefert beides bereits gebuendelt.
    suspend fun getManifest(): Manifest = authedGet("manifest")

    suspend fun runAgent(agentName: String?, path: String, command: String, model: String?): CommandAccepted {
        val body = json.encodeToString(CommandRequest(command, path, model))
        return if (agentName == null) authedPost(body) else authedPost(body, agentName)
    }

    suspend fun runPathCommand(pathName: String, key: String): CommandAccepted =
        authedPost("", "paths", pathName, "commands", key)

    suspend fun getState(id: String): CommandState = authedGet("state", id)

    /** Sends SIGTERM to a still-running command's process; the resulting terminal status ("stopped") arrives via [streamState]/[getState] as usual. */
    suspend fun stopCommand(id: String): CommandAccepted = authedPost("", "state", id, "stop")

    /**
     * Live-Output per Server-Sent Events statt Polling. Emits one [CommandState] per "data:" event; the flow
     * completes normally once the server closes the connection (command no longer running). Callers should
     * fall back to polling [getState] if this flow throws before a terminal status was emitted (e.g. a proxy
     * without streaming support).
     */
    fun streamState(id: String): Flow<CommandState> = flow<CommandState> {
        val session = requireSession()
        val request = Request.Builder()
            .url(
                urlBuilder(session.connection.host, session.connection.port)
                    .addPathSegment("state").addPathSegment(id).addPathSegment("stream").build(),
            )
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .get()
            .build()
        try {
            streamingClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw errorFrom(response)
                val source = response.body?.source() ?: throw ApiException(response.code, "Leere Antwort.")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith(SSE_DATA_PREFIX)) {
                        emit(json.decodeFromString<CommandState>(line.removePrefix(SSE_DATA_PREFIX)))
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            throw error
        } catch (error: Exception) {
            throw ApiException(null, error.message ?: "Netzwerkfehler.", error)
        }
    }.flowOn(Dispatchers.IO)

    /** GET /commands/:pathName: paginiert per `limit`/`offset` (Server-Default `limit=5`, neueste zuerst). */
    suspend fun getCommands(pathName: String, limit: Int? = null, offset: Int? = null): CommandList {
        val session = requireSession()
        val urlBuilder = urlBuilder(session.connection.host, session.connection.port)
            .addPathSegment("commands").addPathSegment(pathName)
        if (limit != null) urlBuilder.addQueryParameter("limit", limit.toString())
        if (offset != null) urlBuilder.addQueryParameter("offset", offset.toString())
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .get()
        return execute(request)
    }

    suspend fun getStats(pathName: String, hours: Int? = null): ProjectStats {
        val session = requireSession()
        val urlBuilder = urlBuilder(session.connection.host, session.connection.port)
            .addPathSegment("stats").addPathSegment(pathName)
        if (hours != null) urlBuilder.addQueryParameter("hours", hours.toString())
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .get()
        return execute(request)
    }

    suspend fun getUsage(): List<UsageLimit> = authedGet<UsageResponse>("usage").limits

    suspend fun listHostedFiles(pathName: String, hostedName: String): FileList =
        authedGet("files", pathName, hostedName)

    suspend fun downloadHostedEntry(
        pathName: String,
        hostedName: String,
        destinationDir: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = downloadTo(destinationDir, hostedName, onProgress, "files", pathName, hostedName)

    suspend fun downloadHostedFile(
        pathName: String,
        hostedName: String,
        fileName: String,
        destinationDir: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = downloadTo(destinationDir, fileName, onProgress, "files", pathName, hostedName, fileName)

    suspend fun listTickets(pathName: String, status: String? = null): TicketList {
        val session = requireSession()
        val urlBuilder = urlBuilder(session.connection.host, session.connection.port)
            .addPathSegment("tickets").addPathSegment(pathName)
        if (status != null) urlBuilder.addQueryParameter("status", status)
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .get()
        return execute(request)
    }

    suspend fun listAllTickets(status: String? = null): TicketList {
        val session = requireSession()
        val urlBuilder = urlBuilder(session.connection.host, session.connection.port)
            .addPathSegment("tickets")
        if (status != null) urlBuilder.addQueryParameter("status", status)
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .get()
        return execute(request)
    }

    suspend fun createTicket(pathName: String, text: String): Ticket {
        val session = requireSession()
        val request = Request.Builder()
            .url(
                urlBuilder(session.connection.host, session.connection.port)
                    .addPathSegment("tickets").addPathSegment(pathName).build(),
            )
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .post(json.encodeToString(TicketCreateRequest(text)).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    suspend fun getTicket(pathName: String, id: Int): Ticket = authedGet("tickets", pathName, id.toString())

    suspend fun collect(pathName: String): CollectSummary = authedPost("", "collect", pathName)

    suspend fun getFeedback(pathName: String): FeedbackList = authedGet("feedback", pathName)

    suspend fun updateFeedback(id: Int, text: String): FeedbackEntry =
        authedPatch(json.encodeToString(FeedbackPatchRequest(text)), "feedback", id.toString())

    suspend fun deleteFeedback(id: Int): MessageResponse = authedDelete("feedback", id.toString())

    suspend fun updateTicket(pathName: String, id: Int, patch: TicketPatchRequest): Ticket =
        authedPatch(json.encodeToString(patch), "tickets", pathName, id.toString())

    suspend fun deleteTicket(pathName: String, id: Int): MessageResponse =
        authedDelete("tickets", pathName, id.toString())

    suspend fun getConfig(): JsonElement = authedGet("config")

    suspend fun putConfig(rawJson: String): ConfigPutResponse = authedPut(rawJson, "config")

    suspend fun getConfigVersions(): ConfigVersionsResponse = authedGet("config", "versions")

    suspend fun getConfigPointer(): ConfigPointerResponse = authedGet("config", "pointer")

    suspend fun setConfigPointer(versionId: Int?): ConfigPointerUpdateResponse {
        val body = if (versionId == null) "{\"embedded\":true}" else "{\"versionId\":$versionId}"
        return authedPut(body, "config", "pointer")
    }

    private suspend inline fun <reified T> authedGet(vararg segments: String): T {
        val session = requireSession()
        val request = Request.Builder()
            .url(
                urlBuilder(session.connection.host, session.connection.port)
                    .apply { segments.forEach(::addPathSegment) }.build(),
            )
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .get()
        return execute(request)
    }

    private suspend inline fun <reified T> authedPost(body: String, vararg segments: String): T {
        val session = requireSession()
        val request = Request.Builder()
            .url(
                urlBuilder(session.connection.host, session.connection.port)
                    .apply { segments.forEach(::addPathSegment) }.build(),
            )
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        return execute(request)
    }

    private suspend inline fun <reified T> authedPatch(body: String, vararg segments: String): T {
        val session = requireSession()
        val request = Request.Builder()
            .url(
                urlBuilder(session.connection.host, session.connection.port)
                    .apply { segments.forEach(::addPathSegment) }.build(),
            )
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .patch(body.toRequestBody(JSON_MEDIA_TYPE))
        return execute(request)
    }

    private suspend inline fun <reified T> authedPut(body: String, vararg segments: String): T {
        val session = requireSession()
        val request = Request.Builder()
            .url(
                urlBuilder(session.connection.host, session.connection.port)
                    .apply { segments.forEach(::addPathSegment) }.build(),
            )
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
        return execute(request)
    }

    private suspend inline fun <reified T> authedDelete(vararg segments: String): T {
        val session = requireSession()
        val request = Request.Builder()
            .url(
                urlBuilder(session.connection.host, session.connection.port)
                    .apply { segments.forEach(::addPathSegment) }.build(),
            )
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .delete()
        return execute(request)
    }

    private suspend fun downloadTo(
        destinationDir: File,
        fallbackFileName: String,
        onProgress: (DownloadProgress) -> Unit,
        vararg segments: String,
    ): File {
        val session = requireSession()
        val request = Request.Builder()
            .url(
                urlBuilder(session.connection.host, session.connection.port)
                    .apply { segments.forEach(::addPathSegment) }.build(),
            )
            .header(AUTHORIZATION_HEADER, "Bearer ${session.auth?.token}")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw errorFrom(response)
                    val body = response.body ?: throw ApiException(response.code, "Leere Antwort.")
                    val fileName = contentDispositionFileName(response.header("Content-Disposition")) ?: fallbackFileName
                    destinationDir.mkdirs()
                    val destination = File(destinationDir, fileName)
                    writeBodyWithProgress(body, destination, onProgress)
                    destination
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                throw error
            } catch (error: Exception) {
                throw ApiException(null, error.message ?: "Download fehlgeschlagen.", error)
            }
        }
    }

    private fun writeBodyWithProgress(body: ResponseBody, destination: File, onProgress: (DownloadProgress) -> Unit) {
        val totalBytes = body.contentLength().takeIf { it >= 0 }
        val tracker = DownloadProgressTracker()
        val startNanos = System.nanoTime()
        var lastEmitNanos = startNanos
        var bytesDownloaded = 0L
        val buffer = Buffer()
        body.source().use { source ->
            destination.sink().buffer().use { sink ->
                while (true) {
                    val read = source.read(buffer, DOWNLOAD_CHUNK_SIZE)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    bytesDownloaded += read
                    val now = System.nanoTime()
                    if (now - lastEmitNanos >= PROGRESS_EMIT_INTERVAL_NANOS) {
                        lastEmitNanos = now
                        onProgress(tracker.update((now - startNanos) / 1_000_000, bytesDownloaded, totalBytes))
                    }
                }
            }
        }
        onProgress(tracker.update((System.nanoTime() - startNanos) / 1_000_000, bytesDownloaded, totalBytes))
    }

    private fun contentDispositionFileName(header: String?): String? =
        header?.let { CONTENT_DISPOSITION_FILENAME.find(it)?.groupValues?.get(1) }

    private suspend inline fun <reified T> execute(builder: Request.Builder): T = execute(builder.build())

    private suspend inline fun <reified T> execute(request: Request, client: OkHttpClient = this.client): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ApiException(null, error.message ?: "Netzwerkfehler.", error)
        }
        response.use {
            if (!it.isSuccessful) {
                val error = errorFrom(it)
                if (it.code == 401) {
                    sessionInvalidator.clearAuthSession()
                }
                throw error
            }
            val text = try {
                it.body?.string().orEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw ApiException(it.code, "Fehler beim Lesen der Antwort.", error)
            }
            val result: T = try {
                json.decodeFromString(text)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw ApiException(it.code, "Ungueltige Server-Antwort.", error)
            }
            if (request.header(AUTHORIZATION_HEADER) != null && request.url.encodedPath != AUTH_REFRESH_PATH) {
                scheduleBackgroundRefresh()
            }
            result
        }
    }

    private fun errorFrom(response: Response): ApiException {
        val text = response.body?.string().orEmpty()
        val message = try {
            json.decodeFromString(ErrorResponse.serializer(), text).error
        } catch (error: SerializationException) {
            response.message.ifEmpty { "HTTP ${response.code}" }
        }
        return ApiException(response.code, message)
    }
}
