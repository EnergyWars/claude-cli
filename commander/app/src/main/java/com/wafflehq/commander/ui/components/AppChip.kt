package com.wafflehq.commander.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppTheme

enum class ChipVariant { Assist, Suggestion, Filter, Input }

@Composable
fun AppChip(
    label: String,
    role: AppRole,
    variant: ChipVariant,
    selected: Boolean = false,
    onClick: () -> Unit = {},
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val r = AppTheme.tokens.chip.forRole(role)

    when (variant) {
        ChipVariant.Assist -> AssistChip(
            onClick = onClick,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = r.assistBackground,
                labelColor     = r.assistContent,
            ),
        )

        ChipVariant.Suggestion -> SuggestionChip(
            onClick = onClick,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = r.suggestionBackground,
                labelColor     = r.suggestionContent,
            ),
        )

        ChipVariant.Filter -> FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = r.filterSelectedBackground,
                selectedLabelColor     = r.filterSelectedContent,
                containerColor         = r.filterUnselectedBackground,
                labelColor             = r.filterUnselectedContent,
            ),
        )

        ChipVariant.Input -> InputChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
            colors = InputChipDefaults.inputChipColors(
                selectedContainerColor = r.inputSelectedBackground,
                selectedLabelColor     = r.inputSelectedContent,
                containerColor         = r.inputUnselectedBackground,
                labelColor             = r.inputUnselectedContent,
            ),
            trailingIcon = onRemove?.let {
                {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(InputChipDefaults.IconSize),
                    )
                }
            },
        )
    }
}
