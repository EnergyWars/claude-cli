package com.wafflehq.appgetter.data.api

import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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
import okhttp3.ResponseBody
import okio.Buffer
import okio.buffer
import okio.sink

private val CONTENT_DISPOSITION_FILENAME = Regex("filename=\"([^\"]+)\"")
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val DOWNLOAD_CHUNK_SIZE = 8_192L
private const val PROGRESS_EMIT_INTERVAL_NANOS = 150_000_000L

/** Unauthenticated client for a `cl server`'s public /status, /collections and /feedback endpoints. */
@Singleton
class AppGetterApi @Inject constructor() {

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

    private val json = Json { ignoreUnknownKeys = true }

    private fun urlBuilder(host: String, port: Int): HttpUrl.Builder =
        HttpUrl.Builder().scheme("http").host(host).port(port)

    /** GET /status: 204 without a body, used to probe candidate addresses during auto-discovery. */
    suspend fun probeStatus(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(urlBuilder(host, port).addPathSegment("status").build()).get().build()
        try {
            discoveryClient.newCall(request).execute().use { it.code == 204 }
        } catch (error: java.io.IOException) {
            false
        }
    }

    suspend fun getCollections(host: String, port: Int): CollectionList {
        val request = Request.Builder()
            .url(urlBuilder(host, port).addPathSegment("collections").build())
            .get()
        return execute(request)
    }

    suspend fun downloadCollectionFile(
        host: String,
        port: Int,
        name: String,
        destinationDir: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File {
        val request = Request.Builder()
            .url(urlBuilder(host, port).addPathSegments("collections/get").addPathSegment(name).build())
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw errorFrom(response)
                val body = response.body ?: throw ApiException(response.code, "Leere Antwort.")
                val fileName = contentDispositionFileName(response.header("Content-Disposition")) ?: name
                destinationDir.mkdirs()
                val destination = File(destinationDir, fileName)
                writeBodyWithProgress(body, destination, onProgress)
                destination
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

    /** POST /feedback: kein Auth, legt einen neuen Feedback-Eintrag auf dem Server an. */
    suspend fun sendFeedback(
        host: String,
        port: Int,
        text: String,
        section: String? = null,
        context: String? = null,
    ): FeedbackEntry {
        val request = Request.Builder()
            .url(urlBuilder(host, port).addPathSegment("feedback").build())
            .post(json.encodeToString(FeedbackRequest(text, section, context)).toRequestBody(JSON_MEDIA_TYPE))
        return execute(request)
    }

    private fun contentDispositionFileName(header: String?): String? =
        header?.let { CONTENT_DISPOSITION_FILENAME.find(it)?.groupValues?.get(1) }

    private suspend inline fun <reified T> execute(builder: Request.Builder): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(builder.build()).execute()
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
