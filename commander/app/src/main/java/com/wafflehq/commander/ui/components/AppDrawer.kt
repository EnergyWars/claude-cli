package com.wafflehq.commander.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wafflehq.commander.R
import com.wafflehq.commander.ui.navigation.Routes
import com.wafflehq.commander.ui.theme.AppTheme

private data class DrawerPage(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

@Composable
fun AppDrawer(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    val pages = listOf(
        DrawerPage(Routes.HOME, Icons.Outlined.Home, R.string.header_home),
        DrawerPage(Routes.SETTINGS, Icons.Outlined.Settings, R.string.label_settings),
    )
    ModalDrawerSheet(
        drawerContainerColor = AppTheme.colors.surface,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = AppTheme.colors.onSurface,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
        )
        Text(
            text = stringResource(R.string.nav_section_pages),
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
        )
        pages.forEach { page ->
            NavigationDrawerItem(
                icon = { Icon(page.icon, contentDescription = null) },
                label = { Text(stringResource(page.labelRes)) },
                selected = currentRoute == page.route,
                onClick = { onSelect(page.route) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
