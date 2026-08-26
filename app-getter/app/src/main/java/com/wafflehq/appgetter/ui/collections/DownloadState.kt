package com.wafflehq.appgetter.ui.collections

import com.wafflehq.appgetter.data.api.CollectedFile

enum class DownloadState { NOT_DOWNLOADED, UP_TO_DATE, UPDATE_AVAILABLE }

/** Vergleicht den Zeitstempel der zuletzt heruntergeladenen Version einer Datei mit dem aktuellen Server-Stand. */
fun downloadState(file: CollectedFile, downloadedTimestamps: Map<String, String>): DownloadState {
    val downloadedTimestamp = downloadedTimestamps[file.name] ?: return DownloadState.NOT_DOWNLOADED
    return if (downloadedTimestamp == file.timestamp) DownloadState.UP_TO_DATE else DownloadState.UPDATE_AVAILABLE
}
