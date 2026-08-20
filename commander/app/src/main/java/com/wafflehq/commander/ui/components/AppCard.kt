package com.wafflehq.commander.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppTheme

enum class CardVariant {
    Filled, Elevated, Outlined
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    role: AppRole? = null,
    variant: CardVariant = CardVariant.Filled,
    content: @Composable () -> Unit,
) {
    val t = AppTheme.tokens.card

    val background = when {
        role != null && variant == CardVariant.Filled    -> t.forRole(role).filledBackground
        role != null && variant == CardVariant.Elevated  -> t.forRole(role).elevatedBackground
        variant == CardVariant.Filled                    -> t.base.filledBackground
        variant == CardVariant.Elevated                  -> t.base.elevatedBackground
        else                                             -> t.base.outlinedBackground
    }

    when (variant) {
        CardVariant.Filled -> Box(
            modifier = modifier.background(background, RoundedCornerShape(AppRadius.card)),
        ) { content() }

        CardVariant.Elevated -> ElevatedCard(
            modifier = modifier,
            shape = RoundedCornerShape(AppRadius.card),
            colors = CardDefaults.elevatedCardColors(containerColor = background),
        ) { content() }

        CardVariant.Outlined -> OutlinedCard(
            modifier = modifier.border(
                1.dp,
                t.base.outlinedBorder.copy(alpha = 0.25f),
                RoundedCornerShape(AppRadius.card),
            ),
            shape = RoundedCornerShape(AppRadius.card),
            colors = CardDefaults.outlinedCardColors(containerColor = t.base.outlinedBackground),
        ) { content() }
    }
}
