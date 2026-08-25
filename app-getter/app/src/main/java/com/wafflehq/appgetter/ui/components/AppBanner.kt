package com.wafflehq.appgetter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.wafflehq.appgetter.ui.theme.AppRadius
import com.wafflehq.appgetter.ui.theme.AppRole
import com.wafflehq.appgetter.ui.theme.AppSpacing
import com.wafflehq.appgetter.ui.theme.AppTheme

@Composable
fun AppBanner(
    title: String,
    body: String,
    role: AppRole,
    icon: ImageVector? = null,
    action: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
) {
    val r = AppTheme.tokens.banner.forRole(role)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(r.background, RoundedCornerShape(AppRadius.card))
            .padding(AppSpacing.lg),
    ) {
        Row {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = r.icon,
                    modifier = Modifier.padding(end = AppSpacing.md),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = r.title,
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = r.body,
                )
            }
        }

        if (action != null) {
            Spacer(modifier = Modifier.height(AppSpacing.md))
            TextButton(onClick = action.second) {
                Text(text = action.first, color = r.actionContent)
            }
        }
    }
}
