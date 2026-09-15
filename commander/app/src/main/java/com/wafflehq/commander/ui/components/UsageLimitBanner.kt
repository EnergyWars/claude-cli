package com.wafflehq.commander.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.UsageLimit
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme
import java.time.Instant

private const val USAGE_PACE_WARNING_THRESHOLD = 100
private const val USAGE_ROLE_ERROR_THRESHOLD = 90
private const val USAGE_ROLE_WARNING_THRESHOLD = 70

fun usageRoleFor(percentUsed: Int): AppRole = when {
    percentUsed >= USAGE_ROLE_ERROR_THRESHOLD -> AppRole.Error
    percentUsed >= USAGE_ROLE_WARNING_THRESHOLD -> AppRole.Warning
    else -> AppRole.Neutral
}

@Composable
fun UsageLimitBanner(
    limits: List<UsageLimit>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    lastUpdatedAt: Instant?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (limits.isEmpty()) return
    AppCard(modifier = modifier.fillMaxWidth(), role = AppRole.Neutral, variant = CardVariant.Filled) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.usage_banner_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.padding(AppSpacing.sm))
                } else {
                    AppIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.usage_banner_refresh),
                        role = AppRole.Neutral,
                        onClick = onRefresh,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.usage_banner_collapse else R.string.usage_banner_expand,
                    ),
                    tint = AppTheme.colors.onSurfaceVariant,
                )
            }
            if (lastUpdatedAt != null) {
                Text(
                    text = stringResource(R.string.usage_banner_last_updated, formatClockTime(lastUpdatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
            if (expanded) {
                limits.forEach { limit -> UsageLimitRow(limit) }
            }
        }
    }
}

@Composable
private fun UsageLimitRow(limit: UsageLimit) {
    val roleColors = AppTheme.colors.forRole(usageRoleFor(limit.percentUsed))
    val resetAt = remember(limit.resetsAt) { parseUsageResetAt(limit.resetsAt) }
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
        Text(
            text = if (resetAt != null) {
                val countdown = usageResetCountdown(resetAt)
                val hoursText = pluralStringResource(R.plurals.usage_hours_short, countdown.hours, countdown.hours)
                val minutesText = pluralStringResource(
                    R.plurals.usage_minutes_short,
                    countdown.minutes,
                    countdown.minutes,
                )
                val relative = when {
                    countdown.hours > 0 && countdown.minutes > 0 ->
                        stringResource(R.string.usage_banner_resets_in, hoursText, minutesText)
                    countdown.hours > 0 -> stringResource(R.string.usage_banner_resets_in_single, hoursText)
                    else -> stringResource(R.string.usage_banner_resets_in_single, minutesText)
                }
                stringResource(R.string.usage_banner_resets_at_time, formatUsageResetClockTime(resetAt), relative)
            } else {
                stringResource(R.string.usage_banner_resets_at, limit.resetsAt)
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
        val pace = resetAt?.let { computeWeeklyUsagePace(limit, it) }
        if (pace != null) {
            Text(
                text = stringResource(R.string.usage_banner_pace, pace.paceRatioPercent),
                style = MaterialTheme.typography.bodySmall,
                color = if (pace.paceRatioPercent > USAGE_PACE_WARNING_THRESHOLD) {
                    AppTheme.colors.forRole(AppRole.Warning).accent
                } else {
                    AppTheme.colors.onSurfaceVariant
                },
            )
        }
    }
}
