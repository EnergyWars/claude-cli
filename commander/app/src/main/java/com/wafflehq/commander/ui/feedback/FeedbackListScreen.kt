package com.wafflehq.commander.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wafflehq.commander.R
import com.wafflehq.commander.data.api.FeedbackEntry
import com.wafflehq.commander.ui.components.AppBanner
import com.wafflehq.commander.ui.components.AppButton
import com.wafflehq.commander.ui.components.AppCard
import com.wafflehq.commander.ui.components.AppConfirmDialog
import com.wafflehq.commander.ui.components.AppIconButton
import com.wafflehq.commander.ui.components.AppTextField
import com.wafflehq.commander.ui.components.ButtonVariant
import com.wafflehq.commander.ui.components.SettingsDropdownField
import com.wafflehq.commander.ui.components.SettingsScaffold
import com.wafflehq.commander.ui.navigation.hiltViewModel
import com.wafflehq.commander.ui.theme.AppRadius
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

@Composable
fun FeedbackListScreen(
    onBack: () -> Unit,
    viewModel: FeedbackListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<FeedbackEntry?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    SettingsScaffold(
        title = stringResource(R.string.feedback_title),
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
            } else if (state.feedback.isEmpty()) {
                Text(
                    text = stringResource(R.string.feedback_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                items(state.feedback, key = { it.id }) { entry ->
                    FeedbackRow(
                        entry = entry,
                        isEditing = state.editingId == entry.id,
                        editText = state.editText,
                        onEditTextChange = viewModel::onEditTextChange,
                        onStartEdit = { viewModel.startEdit(entry) },
                        onSaveEdit = viewModel::saveEdit,
                        onCancelEdit = viewModel::cancelEdit,
                        onDelete = { pendingDelete = entry },
                        onConvert = { viewModel.startConvert(entry) },
                    )
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AppConfirmDialog(
            title = stringResource(R.string.feedback_delete_confirm_title),
            body = stringResource(R.string.feedback_delete_confirm_body),
            confirmText = stringResource(R.string.feedback_delete),
            dismissText = stringResource(R.string.label_cancel),
            onConfirm = {
                viewModel.delete(toDelete)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (state.convertingEntry != null) {
        FeedbackConvertDialog(
            projectNames = state.projectNames,
            selectedIndex = state.convertProjectIndex,
            onProjectSelected = viewModel::onConvertProjectSelected,
            onConfirm = viewModel::confirmConvert,
            onDismiss = viewModel::cancelConvert,
        )
    }
}

@Composable
private fun FeedbackRow(
    entry: FeedbackEntry,
    isEditing: Boolean,
    editText: String,
    onEditTextChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDelete: () -> Unit,
    onConvert: () -> Unit,
) {
    AppCard(role = AppRole.Neutral, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AppSpacing.lg), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            if (isEditing) {
                AppTextField(
                    value = editText,
                    onValueChange = onEditTextChange,
                    label = stringResource(R.string.feedback_title),
                    role = AppRole.Neutral,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    AppButton(
                        text = stringResource(R.string.feedback_save),
                        role = AppRole.Primary,
                        variant = ButtonVariant.Text,
                        onClick = onSaveEdit,
                    )
                    AppButton(
                        text = stringResource(R.string.feedback_cancel),
                        role = AppRole.Neutral,
                        variant = ButtonVariant.Text,
                        onClick = onCancelEdit,
                    )
                }
            } else {
                Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.onSurface)
                val section = entry.section
                if (section != null) {
                    Text(
                        text = stringResource(R.string.feedback_section, section),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
                Text(entry.createdAt, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    AppIconButton(
                        icon = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.feedback_edit),
                        role = AppRole.Neutral,
                        onClick = onStartEdit,
                    )
                    AppIconButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = stringResource(R.string.feedback_convert_to_ticket),
                        role = AppRole.Primary,
                        onClick = onConvert,
                    )
                    AppIconButton(
                        icon = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.feedback_delete),
                        role = AppRole.Error,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackConvertDialog(
    projectNames: List<String>,
    selectedIndex: Int,
    onProjectSelected: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.dialog),
        containerColor = AppTheme.tokens.surface.surface,
        titleContentColor = AppTheme.tokens.surface.onSurface,
        textContentColor = AppTheme.tokens.surface.onSurface,
        title = { Text(stringResource(R.string.feedback_convert_title)) },
        text = {
            SettingsDropdownField(
                label = stringResource(R.string.feedback_convert_project_label),
                value = projectNames.getOrNull(selectedIndex).orEmpty(),
                options = projectNames,
                selectedIndex = selectedIndex,
                onSelect = onProjectSelected,
            )
        },
        confirmButton = {
            AppButton(
                text = stringResource(R.string.feedback_convert_confirm),
                role = AppRole.Primary,
                variant = ButtonVariant.Text,
                enabled = projectNames.isNotEmpty(),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            AppButton(
                text = stringResource(R.string.label_cancel),
                role = AppRole.Neutral,
                variant = ButtonVariant.Text,
                onClick = onDismiss,
            )
        },
    )
}
