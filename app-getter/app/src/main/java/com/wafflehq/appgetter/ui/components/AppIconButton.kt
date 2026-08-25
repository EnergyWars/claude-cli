package com.wafflehq.appgetter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wafflehq.appgetter.ui.theme.AppRole
import com.wafflehq.appgetter.ui.theme.AppTheme

enum class IconButtonVariant {
    Standard, Filled, Tonal, Outlined
}

@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String,
    role: AppRole,
    variant: IconButtonVariant = IconButtonVariant.Standard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = AppTheme.tokens.iconButton
    val r = t.forRole(role)

    when (variant) {
        IconButtonVariant.Standard -> IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) r.standardContent else t.disabledContent,
            )
        }

        IconButtonVariant.Filled -> FilledIconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = IconButtonColors(
                containerColor         = r.filledBackground,
                contentColor           = r.filledContent,
                disabledContainerColor = t.disabledBackground,
                disabledContentColor   = t.disabledContent,
            ),
        ) { Icon(imageVector = icon, contentDescription = contentDescription) }

        IconButtonVariant.Tonal -> FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = IconButtonColors(
                containerColor         = r.tonalBackground,
                contentColor           = r.tonalContent,
                disabledContainerColor = t.disabledBackground,
                disabledContentColor   = t.disabledContent,
            ),
        ) { Icon(imageVector = icon, contentDescription = contentDescription) }

        IconButtonVariant.Outlined -> OutlinedIconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            border = BorderStroke(1.dp, if (enabled) r.outlinedBorder else t.disabledContent),
            colors = IconButtonColors(
                containerColor         = AppTheme.tokens.surface.surface,
                contentColor           = r.outlinedContent,
                disabledContainerColor = AppTheme.tokens.surface.surface,
                disabledContentColor   = t.disabledContent,
            ),
        ) { Icon(imageVector = icon, contentDescription = contentDescription) }
    }
}
