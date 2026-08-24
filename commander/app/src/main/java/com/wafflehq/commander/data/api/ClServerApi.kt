package com.wafflehq.commander.data.api

import com.wafflehq.commander.data.connection.ConnectionSource
import com.wafflehq.commander.data.connection.Session
import com.wafflehq.commander.data.connection.SessionInvalidator
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import okio.sink

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val AUTHORIZATION_HEADER = "Authorization"
private val CONTENT_DISPOSITION_FILENAME = Regex("filename=\"([^\"]+)\"")

@Singleton
class ClServerApi @Inject constructor(
    private val connectionSource: ConnectionSource,
    private val sessionInvalidator: SessionInvalidator,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** POST /tickets/:pathName runs the ticket agent synchronously server-side and can take much longer than 30s. */
    private val ticketAgentClient = client.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /** Short timeouts for scanning many local-network addresses at once during auto-discovery. */
    private val discoveryClient = client.newBuilder()
        .connectTimeout(400, TimeUnit.MILLISECONDS)
        .readTimeout(400, TimeUnit.MILLISECONDS)
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
        } catch (error: java.io.IOException) {
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

    suspend fun listHostedFiles(pathName: String, hostedName: String): FileList =
        authedGet("files", pathName, hostedName)

    suspend fun downloadHostedEntry(pathName: String, hostedName: String, destinationDir: File): File =
        downloadTo(destinationDir, hostedName, "files", pathName, hostedName)

    suspend fun downloadHostedFile(pathName: String, hostedName: String, fileName: String, destinationDir: File): File =
        downloadTo(destinationDir, fileName, "files", pathName, hostedName, fileName)

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
        return execute(request, ticketAgentClient)
    }

    suspend fun getTicket(pathName: String, id: Int): Ticket = authedGet("tickets", pathName, id.toString())

    suspend fun updateTicket(pathName: String, id: Int, patch: TicketPatchRequest): Ticket =
        authedPatch(json.encodeToString(patch), "tickets", pathName, id.toString())

    suspend fun deleteTicket(pathName: String, id: Int): MessageResponse =
        authedDelete("tickets", pathName, id.toString())

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

    private suspend fun downloadTo(destinationDir: File, fallbackFileName: String, vararg segments: String): File {
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw errorFrom(response)
                val body = response.body ?: throw ApiException(response.code, "Leere Antwort.")
                val fileName = contentDispositionFileName(response.header("Content-Disposition")) ?: fallbackFileName
                destinationDir.mkdirs()
                val destination = File(destinationDir, fileName)
                destination.sink().buffer().use { sink -> sink.writeAll(body.source()) }
                destination
            }
        }
    }

    private fun contentDispositionFileName(header: String?): String? =
        header?.let { CONTENT_DISPOSITION_FILENAME.find(it)?.groupValues?.get(1) }

    private suspend inline fun <reified T> execute(builder: Request.Builder): T = execute(builder.build())

    private suspend inline fun <reified T> execute(request: Request, client: OkHttpClient = this.client): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (error: java.io.IOException) {
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
            val text = it.body?.string().orEmpty()
            try {
                json.decodeFromString(text)
            } catch (error: SerializationException) {
                throw ApiException(it.code, "Ungueltige Server-Antwort.", error)
            }
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
