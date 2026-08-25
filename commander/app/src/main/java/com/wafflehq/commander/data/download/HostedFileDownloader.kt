package com.wafflehq.commander.data.download

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.wafflehq.commander.data.api.ClServerApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Reine Funktion, damit die APK-Erkennung ohne Android-Kontext testbar ist. */
fun isApkFileName(name: String): Boolean = name.endsWith(".apk", ignoreCase = true)

@Singleton
class HostedFileDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ClServerApi,
) {
    private val downloadsDir: File
        get() = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    suspend fun downloadEntry(pathName: String, hostedName: String): File =
        api.downloadHostedEntry(pathName, hostedName, downloadsDir)

    suspend fun downloadFile(pathName: String, hostedName: String, fileName: String): File =
        api.downloadHostedFile(pathName, hostedName, fileName, downloadsDir)

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

    fun openOrInstallIntent(file: File): Intent =
        if (isApkFileName(file.name)) installIntent(file) else shareIntent(file)
}
