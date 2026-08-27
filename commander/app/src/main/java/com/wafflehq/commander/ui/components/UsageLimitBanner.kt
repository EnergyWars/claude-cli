package com.wafflehq.commander.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.wafflehq.commander.data.api.UsageLimit
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

private const val USAGE_ROLE_ERROR_THRESHOLD = 90
private const val USAGE_ROLE_WARNING_THRESHOLD = 70

fun usageRoleFor(percentUsed: Int): AppRole = when {
    percentUsed >= USAGE_ROLE_ERROR_THRESHOLD -> AppRole.Error
    percentUsed >= USAGE_ROLE_WARNING_THRESHOLD -> AppRole.Warning
    else -> AppRole.Neutral
}

@Composable
fun UsageLimitBanner(limits: List<UsageLimit>, modifier: Modifier = Modifier) {
    if (limits.isEmpty()) return
    AppCard(modifier = modifier.fillMaxWidth(), role = AppRole.Neutral, variant = CardVariant.Filled) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            limits.forEach { limit -> UsageLimitRow(limit) }
        }
    }
}

@Composable
private fun UsageLimitRow(limit: UsageLimit) {
    val roleColors = AppTheme.colors.forRole(usageRoleFor(limit.percentUsed))
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = limit.label,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = "${limit.percentUsed}%",
                style = MaterialTheme.typography.labelMedium,
                color = roleColors.accent,
            )
        }
        LinearProgressIndicator(
            progress = { limit.percentUsed.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(AppRadius.pill)),
            color = roleColors.accent,
            trackColor = AppTheme.colors.surfaceVariant,
        )
    }
}
