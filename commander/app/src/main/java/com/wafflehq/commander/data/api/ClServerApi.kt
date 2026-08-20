package com.wafflehq.commander.data.api

import com.wafflehq.commander.data.connection.Connection
import com.wafflehq.commander.data.connection.ConnectionSource
import com.wafflehq.commander.data.totp.TotpGenerator
import java.io.File
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
private const val TOTP_HEADER = "X-TOTP-Code"
private val CONTENT_DISPOSITION_FILENAME = Regex("filename=\"([^\"]+)\"")

@Singleton
class ClServerApi @Inject constructor(
    private val connectionSource: ConnectionSource,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun urlBuilder(host: String, port: Int): HttpUrl.Builder =
        HttpUrl.Builder().scheme("http").host(host).port(port)

    private suspend fun requireConnection(): Connection =
        connectionSource.connection.first()
            ?: throw ApiException(null, "Keine Verbindung konfiguriert.")

    // -- Unauthenticated / pairing calls (host+port passed explicitly, no saved Connection yet) --

    suspend fun health(host: String, port: Int): HealthResponse =
        execute(Request.Builder().url(urlBuilder(host, port).addPathSegment("health").build()).get())

    suspend fun authStatus(host: String, port: Int): AuthStatusResponse =
        execute(Request.Builder().url(urlBuilder(host, port).addPathSegments("auth/status").build()).get())

    suspend fun setupAuth(host: String, port: Int): AuthSetupResponse =
        execute(
            Request.Builder()
                .url(urlBuilder(host, port).addPathSegments("auth/setup").build())
                .post("".toRequestBody(null))
        )

    suspend fun confirmAuthSetup(host: String, port: Int, code: String): MessageResponse =
        execute(
            Request.Builder()
                .url(urlBuilder(host, port).addPathSegments("auth/setup/confirm").build())
                .post(json.encodeToString(AuthSetupConfirmRequest(code)).toRequestBody(JSON_MEDIA_TYPE))
        )

    /** Tests a manually-entered secret against a protected endpoint without saving it. */
    suspend fun verifySecret(host: String, port: Int, secret: String): Boolean = try {
        execute<PathList>(
            Request.Builder()
                .url(urlBuilder(host, port).addPathSegments("paths").build())
                .header(TOTP_HEADER, TotpGenerator.generate(secret))
                .get(),
        )
        true
    } catch (error: ApiException) {
        if (error.httpCode == 401) false else throw error
    }

    // -- Authenticated calls (use the saved Connection) --

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

    private suspend inline fun <reified T> authedGet(vararg segments: String): T {
        val connection = requireConnection()
        val request = Request.Builder()
            .url(urlBuilder(connection.host, connection.port).apply { segments.forEach(::addPathSegment) }.build())
            .header(TOTP_HEADER, TotpGenerator.generate(connection.totpSecret))
            .get()
        return execute(request)
    }

    private suspend inline fun <reified T> authedPost(body: String, vararg segments: String): T {
        val connection = requireConnection()
        val request = Request.Builder()
            .url(urlBuilder(connection.host, connection.port).apply { segments.forEach(::addPathSegment) }.build())
            .header(TOTP_HEADER, TotpGenerator.generate(connection.totpSecret))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        return execute(request)
    }

    private suspend fun downloadTo(destinationDir: File, fallbackFileName: String, vararg segments: String): File {
        val connection = requireConnection()
        val request = Request.Builder()
            .url(urlBuilder(connection.host, connection.port).apply { segments.forEach(::addPathSegment) }.build())
            .header(TOTP_HEADER, TotpGenerator.generate(connection.totpSecret))
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

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (error: java.io.IOException) {
            throw ApiException(null, error.message ?: "Netzwerkfehler.", error)
        }
        response.use {
            if (!it.isSuccessful) throw errorFrom(it)
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
