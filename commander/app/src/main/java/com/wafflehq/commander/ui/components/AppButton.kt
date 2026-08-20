package com.wafflehq.commander.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppTheme

enum class ButtonVariant {
    Filled, Tonal, Elevated, Outlined, Text
}

@Composable
fun AppButton(
    text: String,
    role: AppRole,
    variant: ButtonVariant = ButtonVariant.Filled,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = AppTheme.tokens.button
    val r = t.forRole(role)

    when (variant) {
        ButtonVariant.Filled -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(AppRadius.button),
            colors = ButtonColors(
                containerColor         = r.filledBackground,
                contentColor           = r.filledContent,
                disabledContainerColor = t.disabledBackground,
                disabledContentColor   = t.disabledContent,
            ),
        ) { Text(text) }

        ButtonVariant.Tonal -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(AppRadius.button),
            colors = ButtonColors(
                containerColor         = r.tonalBackground,
                contentColor           = r.tonalContent,
                disabledContainerColor = t.disabledBackground,
                disabledContentColor   = t.disabledContent,
            ),
        ) { Text(text) }

        ButtonVariant.Elevated -> ElevatedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(AppRadius.button),
            colors = ButtonColors(
                containerColor         = r.elevatedBackground,
                contentColor           = r.elevatedContent,
                disabledContainerColor = t.disabledBackground,
                disabledContentColor   = t.disabledContent,
            ),
        ) { Text(text) }

        ButtonVariant.Outlined -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(AppRadius.button),
            colors = ButtonColors(
                containerColor         = AppTheme.tokens.surface.surface,
                contentColor           = r.outlinedContent,
                disabledContainerColor = AppTheme.tokens.surface.surface,
                disabledContentColor   = t.disabledContent,
            ),
            border = BorderStroke(1.dp, if (enabled) r.outlinedBorder else t.disabledContent),
        ) { Text(text) }

        ButtonVariant.Text -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(AppRadius.button),
            colors = ButtonColors(
                containerColor         = AppTheme.tokens.surface.surface,
                contentColor           = r.textContent,
                disabledContainerColor = AppTheme.tokens.surface.surface,
                disabledContentColor   = t.disabledContent,
            ),
        ) { Text(text) }
    }
}
