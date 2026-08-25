package com.wafflehq.appgetter.data.api

import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.sink

private val CONTENT_DISPOSITION_FILENAME = Regex("filename=\"([^\"]+)\"")

/** Unauthenticated client for a `cl server`'s public /status and /collections endpoints. */
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

    suspend fun downloadCollectionFile(host: String, port: Int, name: String, destinationDir: File): File {
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
                destination.sink().buffer().use { sink -> sink.writeAll(body.source()) }
                destination
            }
        }
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
