package com.wafflehq.appgetter.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wafflehq.appgetter.R
import com.wafflehq.appgetter.ui.components.AppButton
import com.wafflehq.appgetter.ui.components.AppTextField
import com.wafflehq.appgetter.ui.components.ButtonVariant
import com.wafflehq.appgetter.ui.theme.AppRadius
import com.wafflehq.appgetter.ui.theme.AppRole
import com.wafflehq.appgetter.ui.theme.AppSpacing
import com.wafflehq.appgetter.ui.theme.AppTheme

@Composable
fun FeedbackDialog(
    section: String,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.dialog),
        containerColor = AppTheme.tokens.surface.surface,
        titleContentColor = AppTheme.tokens.surface.onSurface,
        textContentColor = AppTheme.tokens.surface.onSurface,
        title = { Text(stringResource(R.string.feedback_dialog_title, section)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                AppTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = stringResource(R.string.feedback_text_label),
                    role = AppRole.Neutral,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            AppButton(
                text = stringResource(R.string.feedback_submit),
                role = AppRole.Primary,
                variant = ButtonVariant.Text,
                enabled = text.isNotBlank(),
                onClick = onSend,
            )
        },
        dismissButton = {
            AppButton(
                text = stringResource(R.string.label_cancel),
                role = AppRole.Neutral,
                variant = ButtonVariant.Text,
                onClick = onDismiss,
            )
        },
    )
}
