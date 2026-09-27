package com.wafflehq.commander.ui.goals

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.GoalFile
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.CardVariant
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.history.formatTimestamp
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme
import com.wafflehq.commander.ui.theme.GeistMono
import kotlinx.coroutines.launch

@Composable
fun GoalsScreen(
    onBack: () -> Unit,
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.goals_copied)

    fun copyContent(goal: GoalFile) {
        coroutineScope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(goal.name, goal.content)))
        }
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }

    SettingsScaffold(
        title = stringResource(R.string.goals_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            val error = state.error
            if (error != null) {
                AppBanner(title = stringResource(R.string.setup_error_title), body = error, role = AppRole.Error)
            }

            if (state.loading) {
                CircularProgressIndicator()
            } else if (error == null && state.goals.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.goals_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = AppSpacing.bottomSafeArea),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                items(state.goals, key = { it.name }) { goal ->
                    GoalCard(goal = goal, onCopy = { copyContent(goal) })
                }
            }
        }
    }
}

@Composable
private fun GoalCard(goal: GoalFile, onCopy: () -> Unit) {
    AppCard(role = AppRole.Neutral, variant = CardVariant.Outlined, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(
                        text = goal.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.goals_modified_at, formatTimestamp(goal.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
                AppIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.goals_copy),
                    role = AppRole.Primary,
                    onClick = onCopy,
                )
            }
            Text(
                text = goal.content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
                color = AppTheme.colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.surfaceVariant, RoundedCornerShape(AppRadius.card))
                    .padding(AppSpacing.md),
            )
        }
    }
}
