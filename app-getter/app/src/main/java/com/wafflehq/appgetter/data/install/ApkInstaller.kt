package com.wafflehq.appgetter.data.install

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.wafflehq.appgetter.data.api.AppGetterApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

@Singleton
class ApkInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: AppGetterApi,
) {
    private val downloadsDir: File
        get() = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    suspend fun downloadFile(host: String, port: Int, name: String): File =
        api.downloadCollectionFile(host, port, name, downloadsDir)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
