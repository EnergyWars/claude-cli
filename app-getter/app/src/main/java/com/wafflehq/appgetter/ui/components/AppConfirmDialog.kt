package com.wafflehq.appgetter.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.wafflehq.appgetter.ui.theme.AppRadius
import com.wafflehq.appgetter.ui.theme.AppRole
import com.wafflehq.appgetter.ui.theme.AppTheme

@Composable
fun AppConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmRole: AppRole = AppRole.Error,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.dialog),
        containerColor = AppTheme.tokens.surface.surface,
        titleContentColor = AppTheme.tokens.surface.onSurface,
        textContentColor = AppTheme.tokens.surface.onSurface,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            AppButton(
                text = confirmText,
                role = confirmRole,
                variant = ButtonVariant.Text,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            AppButton(
                text = dismissText,
                role = AppRole.Neutral,
                variant = ButtonVariant.Text,
                onClick = onDismiss,
            )
        },
    )
}
