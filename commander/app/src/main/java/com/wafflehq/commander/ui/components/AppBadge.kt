package com.wafflehq.commander.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun AppStatusPill(
    text: String,
    role: AppRole,
    modifier: Modifier = Modifier,
) {
    val r = AppTheme.tokens.badge.forRole(role)

    Box(
        modifier = modifier
            .background(r.pillBackground, RoundedCornerShape(AppRadius.pill))
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
    ) {
        Text(text = text, color = r.pillContent)
    }
}

@Composable
fun AppBadge(
    count: Int?,
    role: AppRole,
    content: @Composable BoxScope.() -> Unit,
) {
    val r = AppTheme.tokens.badge.forRole(role)

    if (count != null && count > 0) {
        BadgedBox(
            badge = {
                Box(
                    modifier = Modifier
                        .background(r.countBackground, RoundedCornerShape(AppRadius.pill))
                        .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xs),
                ) {
                    Text(text = count.toString(), color = r.countContent)
                }
            },
        ) { content() }
    } else {
        Box { content() }
    }
}
