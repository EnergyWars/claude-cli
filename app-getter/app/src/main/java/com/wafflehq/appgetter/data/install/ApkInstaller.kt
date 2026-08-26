package com.wafflehq.appgetter.data.install

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.wafflehq.appgetter.data.api.ApiException
import com.wafflehq.appgetter.data.api.AppGetterApi
import com.wafflehq.appgetter.data.api.DownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

enum class DownloadPhase { DOWNLOADING, VERIFYING, INSTALLING }

data class DownloadStatus(val phase: DownloadPhase, val progress: DownloadProgress? = null)

@Singleton
class ApkInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: AppGetterApi,
) {
    private val downloadsDir: File
        get() = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    private val _downloadStatus = MutableStateFlow<DownloadStatus?>(null)
    val downloadStatus: StateFlow<DownloadStatus?> = _downloadStatus.asStateFlow()

    suspend fun downloadFile(host: String, port: Int, name: String): File {
        _downloadStatus.value = DownloadStatus(DownloadPhase.DOWNLOADING)
        val file = api.downloadCollectionFile(host, port, name, downloadsDir, onProgress = ::emitProgress)
        _downloadStatus.value = DownloadStatus(DownloadPhase.VERIFYING, _downloadStatus.value?.progress)
        verify(file)
        _downloadStatus.value = DownloadStatus(DownloadPhase.INSTALLING, _downloadStatus.value?.progress)
        return file
    }

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

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shareIntent(file: File): Intent {
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
}
