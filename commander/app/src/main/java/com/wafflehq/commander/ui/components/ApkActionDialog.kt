package com.wafflehq.commander.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun ApkActionDialog(
    fileName: String,
    onInstall: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.dialog),
        containerColor = AppTheme.tokens.surface.surface,
        titleContentColor = AppTheme.tokens.surface.onSurface,
        textContentColor = AppTheme.tokens.surface.onSurface,
        title = { Text(stringResource(R.string.apk_action_title)) },
        text = { Text(fileName) },
        confirmButton = {
            AppButton(
                text = stringResource(R.string.apk_action_install),
                role = AppRole.Primary,
                variant = ButtonVariant.Text,
                onClick = onInstall,
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                AppButton(
                    text = stringResource(R.string.apk_action_delete),
                    role = AppRole.Error,
                    variant = ButtonVariant.Text,
                    onClick = onDelete,
                )
                AppButton(
                    text = stringResource(R.string.apk_action_share),
                    role = AppRole.Neutral,
                    variant = ButtonVariant.Text,
                    onClick = onShare,
                )
            }
        },
    )
}
