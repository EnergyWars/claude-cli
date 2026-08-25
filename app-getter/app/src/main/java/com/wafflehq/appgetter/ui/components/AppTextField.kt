package com.wafflehq.appgetter.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wafflehq.appgetter.ui.theme.AppRadius
import com.wafflehq.appgetter.ui.theme.AppRole
import com.wafflehq.appgetter.ui.theme.AppTheme

enum class TextFieldVariant {
    Outlined, Filled
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    role: AppRole,
    variant: TextFieldVariant = TextFieldVariant.Outlined,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = AppTheme.tokens.textField
    val r = t.forRole(role)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        singleLine = false,
        shape = RoundedCornerShape(AppRadius.textField),
        colors = TextFieldDefaults.colors(
            focusedContainerColor   = r.background,
            unfocusedContainerColor = r.background,
            disabledContainerColor  = t.disabledBackground,
            focusedTextColor        = r.content,
            unfocusedTextColor      = r.content,
            disabledTextColor       = t.disabledContent,
            focusedLabelColor       = r.labelFocused,
            unfocusedLabelColor     = r.labelUnfocused,
            focusedIndicatorColor   = r.borderFocused,
            unfocusedIndicatorColor = r.borderUnfocused,
            errorIndicatorColor     = r.errorBorder,
            errorLabelColor         = r.errorLabel,
        ),
    )
}
