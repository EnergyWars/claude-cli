package com.wafflehq.commander.data.download

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.DownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Reine Funktion, damit die APK-Erkennung ohne Android-Kontext testbar ist. */
fun isApkFileName(name: String): Boolean = name.endsWith(".apk", ignoreCase = true)

enum class DownloadPhase { DOWNLOADING, VERIFYING, INSTALLING, OPENING }

data class DownloadStatus(val phase: DownloadPhase, val progress: DownloadProgress? = null)

@Singleton
class HostedFileDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ClServerApi,
    private val historyRepository: DownloadHistoryRepository,
) {
    private val downloadsDir: File
        get() = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    /** Prozessweiter Scope statt viewModelScope: der Download darf laufen, wenn der Nutzer den Screen wechselt oder die App minimiert. */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadStatus = MutableStateFlow<DownloadStatus?>(null)
    val downloadStatus: StateFlow<DownloadStatus?> = _downloadStatus.asStateFlow()

    private val _activeTarget = MutableStateFlow<DownloadTarget?>(null)
    val activeTarget: StateFlow<DownloadTarget?> = _activeTarget.asStateFlow()

    private val _downloadOutcome = MutableStateFlow<DownloadOutcome?>(null)
    val downloadOutcome: StateFlow<DownloadOutcome?> = _downloadOutcome.asStateFlow()

    /** Neueste Version je Schluessel, aber nur APKs - treibt das Install- statt Download-Icon in den Downloads-/Command-Detail-Screens. */
    val pendingInstalls: Flow<List<DownloadVersion>> = historyRepository.versions.map { all ->
        all.filter { isApkFileName(File(it.filePath).name) }
            .groupBy { it.key }
            .map { (_, group) -> group.last() }
    }

    /**
     * [currentTimestamp] ist der frisch vom Server geladene Stand (`ManifestHostedEntry.timestamp`/`HostedFileEntry.timestamp`).
     * Weicht er vom beim Download gespeicherten Timestamp ab, ist die gecachte Datei veraltet: statt sie zu loeschen,
     * bleibt sie Teil der Versionshistorie - der naechste Aufruf laedt einfach neu herunter und haengt die neue Version an.
     */
    suspend fun resolvePendingInstall(pathName: String, identity: String, currentTimestamp: String?): File? {
        val key = pendingKey(pathName, identity)
        val entry = historyRepository.versions.first().filter { it.key == key }.lastOrNull() ?: return null
        if (currentTimestamp != null && entry.timestamp != null && entry.timestamp != currentTimestamp) {
            return null
        }
        val file = File(entry.filePath)
        if (file.exists() && file.length() > 0L) return file
        historyRepository.deleteVersion(key, entry.filePath)
        return null
    }

    suspend fun deletePendingInstall(pathName: String, identity: String, file: File) {
        file.delete()
        historyRepository.deleteVersion(pendingKey(pathName, identity), file.absolutePath)
    }

    /** Laeuft in [downloadScope] statt im Scope des Aufrufers, damit Tab-Wechsel/App-Minimieren den Download nicht abbrechen; liefert `false`, wenn bereits ein Download laeuft. */
    fun startDownload(pathName: String, hostedName: String, fileName: String?, timestamp: String?): Boolean {
        if (_activeTarget.value != null) return false
        val target = DownloadTarget(pathName, fileName ?: hostedName, hostedName, fileName, timestamp)
        _activeTarget.value = target
        downloadScope.launch { runQueuedDownload(target) }
        return true
    }

    fun consumeDownloadOutcome() {
        _downloadOutcome.value = null
    }

    private suspend fun runQueuedDownload(target: DownloadTarget) {
        try {
            val file = if (target.fileName != null) {
                downloadFile(target.pathName, target.hostedName, target.fileName, target.timestamp)
            } else {
                downloadEntry(target.pathName, target.hostedName, target.timestamp)
            }
            _downloadOutcome.value = DownloadOutcome.Success(target, file)
        } catch (error: ApiException) {
            clearDownloadStatus()
            _downloadOutcome.value = DownloadOutcome.Failure(target, error.message ?: "Download fehlgeschlagen.")
        } finally {
            _activeTarget.value = null
        }
    }

    suspend fun downloadEntry(pathName: String, hostedName: String, timestamp: String?): File =
        runDownload(pathName, hostedName, timestamp) { api.downloadHostedEntry(pathName, hostedName, downloadsDir, onProgress = ::emitProgress) }

    suspend fun downloadFile(pathName: String, hostedName: String, fileName: String, timestamp: String?): File =
        runDownload(pathName, fileName, timestamp) { api.downloadHostedFile(pathName, hostedName, fileName, downloadsDir, onProgress = ::emitProgress) }

    private suspend fun runDownload(pathName: String, identity: String, timestamp: String?, download: suspend () -> File): File {
        _downloadStatus.value = DownloadStatus(DownloadPhase.DOWNLOADING)
        val file = download()
        _downloadStatus.value = DownloadStatus(DownloadPhase.VERIFYING, _downloadStatus.value?.progress)
        verify(file)
        val finalPhase = if (isApkFileName(file.name)) DownloadPhase.INSTALLING else DownloadPhase.OPENING
        _downloadStatus.value = DownloadStatus(finalPhase, _downloadStatus.value?.progress)
        val evicted = historyRepository.recordVersion(
            key = pendingKey(pathName, identity),
            timestamp = timestamp,
            downloadedAt = Instant.now().toString(),
            filePath = file.absolutePath,
        )
        evicted.forEach { File(it.filePath).delete() }
        return file
    }

    private fun pendingKey(pathName: String, identity: String) = "$pathName::$identity"

    private fun verify(file: File) {
        if (!file.exists() || file.length() <= 0L) {
            throw ApiException(null, "Download unvollständig.")
        }
    }

    private fun emitProgress(progress: DownloadProgress) {
        _downloadStatus.value = DownloadStatus(DownloadPhase.DOWNLOADING, progress)
    }

    fun clearDownloadStatus() {
        _downloadStatus.value = null
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shareApkIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openOrInstallIntent(file: File): Intent =
        if (isApkFileName(file.name)) installIntent(file) else shareIntent(file)
}
