package com.wafflehq.appgetter.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wafflehq.appgetter.R
import com.wafflehq.appgetter.data.api.DownloadProgress
import com.wafflehq.appgetter.data.api.fraction
import com.wafflehq.appgetter.data.install.DownloadPhase
import com.wafflehq.appgetter.data.install.DownloadStatus
import com.wafflehq.appgetter.ui.theme.AppSpacing
import com.wafflehq.appgetter.ui.theme.AppTheme

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
        DownloadPhase.VERIFYING -> stringResource(R.string.home_verifying)
        DownloadPhase.INSTALLING -> stringResource(R.string.home_installing)
        else -> stringResource(R.string.home_downloading)
    }
    if (status?.phase != DownloadPhase.DOWNLOADING || progress == null) return phaseLabel
    val percent = fraction?.let { "${(it * 100).toInt()}%" }
    val speed = formatDownloadSpeed(progress.bytesPerSecond)
    val eta = formatDownloadEta(progress.etaSeconds)
    return listOfNotNull(percent, speed, eta).joinToString(" · ").ifEmpty { phaseLabel }
}
