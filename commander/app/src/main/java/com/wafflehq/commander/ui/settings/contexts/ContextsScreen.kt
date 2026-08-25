package com.wafflehq.commander.ui.settings.contexts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.SettingsListRow
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun ContextsScreen(
    onBack: () -> Unit,
    onOpenNew: () -> Unit,
    onOpenEdit: (id: Long) -> Unit,
    viewModel: ContextsViewModel = hiltViewModel(),
) {
    val contexts by viewModel.contexts.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = stringResource(R.string.settings_contexts_title),
        onBack = onBack,
        backDescription = stringResource(R.string.label_back),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(modifier = Modifier.padding(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                AppButton(
                    text = stringResource(R.string.settings_contexts_new),
                    role = AppRole.Primary,
                    onClick = onOpenNew,
                )
                if (contexts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_contexts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
            }
            contexts.forEach { context ->
                SettingsListRow(
                    title = context.name,
                    subtitle = context.value.take(60),
                    onClick = { onOpenEdit(context.id) },
                )
            }
        }
    }
}
