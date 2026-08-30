package com.wafflehq.commander.data.download

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.wafflehq.commander.data.api.ApiException
import com.wafflehq.commander.data.api.ClServerApi
import com.wafflehq.commander.data.api.DownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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

    private val _downloadStatus = MutableStateFlow<DownloadStatus?>(null)
    val downloadStatus: StateFlow<DownloadStatus?> = _downloadStatus.asStateFlow()

    val pendingInstalls: Flow<List<PendingInstall>> = historyRepository.pendingInstalls

    suspend fun resolvePendingInstall(pathName: String, identity: String): File? {
        val key = pendingKey(pathName, identity)
        val filePath = historyRepository.pendingInstalls.first().find { it.key == key }?.filePath ?: return null
        val file = File(filePath)
        if (file.exists() && file.length() > 0L) return file
        historyRepository.clearPendingInstall(key)
        return null
    }

    suspend fun deletePendingInstall(pathName: String, identity: String, file: File) {
        file.delete()
        historyRepository.clearPendingInstall(pendingKey(pathName, identity))
    }

    suspend fun downloadEntry(pathName: String, hostedName: String): File =
        runDownload(pathName, hostedName) { api.downloadHostedEntry(pathName, hostedName, downloadsDir, onProgress = ::emitProgress) }

    suspend fun downloadFile(pathName: String, hostedName: String, fileName: String): File =
        runDownload(pathName, fileName) { api.downloadHostedFile(pathName, hostedName, fileName, downloadsDir, onProgress = ::emitProgress) }

    private suspend fun runDownload(pathName: String, identity: String, download: suspend () -> File): File {
        _downloadStatus.value = DownloadStatus(DownloadPhase.DOWNLOADING)
        val file = download()
        _downloadStatus.value = DownloadStatus(DownloadPhase.VERIFYING, _downloadStatus.value?.progress)
        verify(file)
        val finalPhase = if (isApkFileName(file.name)) DownloadPhase.INSTALLING else DownloadPhase.OPENING
        _downloadStatus.value = DownloadStatus(finalPhase, _downloadStatus.value?.progress)
        if (isApkFileName(file.name)) {
            historyRepository.recordPendingInstall(pendingKey(pathName, identity), file.absolutePath)
        }
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
