package com.wafflehq.commander.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.DownloadProgress
import com.wafflehq.commander.data.api.fraction
import com.wafflehq.commander.data.download.DownloadPhase
import com.wafflehq.commander.data.download.DownloadStatus
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun DownloadProgressIndicator(status: DownloadStatus?, modifier: Modifier = Modifier) {
    val progress = status?.progress
    val fraction = progress?.fraction()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.primary.accent,
                trackColor = AppTheme.colors.surfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.primary.accent,
                trackColor = AppTheme.colors.surfaceVariant,
            )
        }
        Text(
            text = downloadStatusLabel(status, fraction, progress),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun downloadStatusLabel(status: DownloadStatus?, fraction: Float?, progress: DownloadProgress?): String {
    val phaseLabel = when (status?.phase) {
        DownloadPhase.VERIFYING -> stringResource(R.string.download_status_verifying)
        DownloadPhase.INSTALLING -> stringResource(R.string.download_status_installing)
        DownloadPhase.OPENING -> stringResource(R.string.download_status_opening)
        else -> stringResource(R.string.download_status_downloading)
    }
    if (status?.phase != DownloadPhase.DOWNLOADING || progress == null) return phaseLabel
    val percent = fraction?.let { "${(it * 100).toInt()}%" }
    val speed = formatDownloadSpeed(progress.bytesPerSecond)
    val eta = formatDownloadEta(progress.etaSeconds)
    return listOfNotNull(percent, speed, eta).joinToString(" · ").ifEmpty { phaseLabel }
}
