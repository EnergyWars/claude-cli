package com.wafflehq.commander.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.theme.AppTheme

enum class HeaderItem { Menu, Home, Settings, None }

@Composable
fun AppScaffold(
    activeItem: HeaderItem,
    onOpenMenu: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = AppTheme.colors.background,
        topBar = {
            AppHeader(
                activeItem = activeItem,
                onOpenMenu = onOpenMenu,
                onNavigateHome = onNavigateHome,
                onOpenSettings = onOpenSettings,
            )
        },
        content = content,
    )
}

@Composable
fun AppHeader(
    activeItem: HeaderItem,
    onOpenMenu: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            HeaderTab(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Menu,
                label = stringResource(R.string.header_menu),
                contentDescription = stringResource(R.string.cd_menu),
                active = activeItem == HeaderItem.Menu,
                onClick = onOpenMenu,
            )
            HeaderTab(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Home,
                label = stringResource(R.string.header_home),
                contentDescription = stringResource(R.string.cd_home),
                active = activeItem == HeaderItem.Home,
                onClick = onNavigateHome,
            )
            HeaderTab(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Settings,
                label = stringResource(R.string.label_settings),
                contentDescription = stringResource(R.string.cd_open_settings),
                active = activeItem == HeaderItem.Settings,
                onClick = onOpenSettings,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outline),
        )
    }
}

@Composable
private fun HeaderTab(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val background = if (active) colors.secondary.container else Color.Transparent
    val foreground = if (active) colors.secondary.onContainer else colors.onSurface
    val labelColor = if (active) colors.secondary.onContainer else colors.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = foreground,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            maxLines = 1,
        )
    }
}
