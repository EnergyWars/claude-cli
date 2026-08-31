package com.wafflehq.commander.data.download

import java.io.File

data class DownloadTarget(
    val pathName: String,
    val identity: String,
    val hostedName: String,
    val fileName: String?,
    val timestamp: String?,
)

sealed interface DownloadOutcome {
    val target: DownloadTarget

    data class Success(override val target: DownloadTarget, val file: File) : DownloadOutcome
    data class Failure(override val target: DownloadTarget, val message: String) : DownloadOutcome
}
