package com.wafflehq.commander.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wafflehq.commander.ui.command.CommandDetailScreen
import com.wafflehq.commander.ui.components.AppDrawer
import com.wafflehq.commander.ui.home.HomeScreen
import com.wafflehq.commander.ui.login.LoginScreen
import com.wafflehq.commander.ui.pathdetail.PathDetailScreen
import com.wafflehq.commander.ui.run.RunAgentScreen
import com.wafflehq.commander.ui.settings.DisplaySettingsScreen
import com.wafflehq.commander.ui.settings.SettingsScreen
import com.wafflehq.commander.ui.setup.SetupScreen
import com.wafflehq.commander.ui.theme.AppTheme
import com.wafflehq.commander.ui.tickets.TicketDetailScreen
import com.wafflehq.commander.ui.tickets.TicketListScreen
import kotlinx.coroutines.launch

object Routes {
    const val SETUP = "setup"
    const val LOGIN = "login"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SETTINGS_DISPLAY = "settings_display"
    const val RUN_AGENT = "run_agent?agent={agent}&path={path}"
    const val PATH_DETAIL = "path_detail/{pathName}"
    const val COMMAND_DETAIL = "command_detail/{id}?pathName={pathName}"
    const val TICKETS = "tickets?pathName={pathName}"
    const val TICKET_DETAIL = "tickets/{pathName}/{id}"

    fun runAgent(agentCommand: String? = null, pathName: String? = null): String {
        val params = buildList {
            agentCommand?.let { add("agent=${Uri.encode(it)}") }
            pathName?.let { add("path=${Uri.encode(it)}") }
        }
        return if (params.isEmpty()) "run_agent" else "run_agent?${params.joinToString("&")}"
    }

    fun pathDetail(pathName: String): String = "path_detail/${Uri.encode(pathName)}"

    fun commandDetail(id: String, pathName: String? = null): String {
        val query = pathName?.let { "?pathName=${Uri.encode(it)}" }.orEmpty()
        return "command_detail/${Uri.encode(id)}$query"
    }

    fun tickets(pathName: String? = null): String =
        if (pathName != null) "tickets?pathName=${Uri.encode(pathName)}" else "tickets"

    fun ticketDetail(pathName: String, id: Int): String = "tickets/${Uri.encode(pathName)}/$id"
}

private fun NavController.switchTo(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Routes.HOME) { saveState = true }
    }
}

@Composable
fun AppNavHost() {
    val gateViewModel: ConnectionGateViewModel = hiltViewModel()
    val gateState by gateViewModel.gateState.collectAsStateWithLifecycle()

    if (gateState is GateState.Loading) {
        Surface(color = AppTheme.colors.background, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(gateState) {
        if (gateState is GateState.NeedsLogin && currentRoute != Routes.LOGIN && currentRoute != Routes.SETUP) {
            navController.navigate(Routes.LOGIN) { popUpTo(0) }
        }
    }

    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
    val navigateHome: () -> Unit = { navController.switchTo(Routes.HOME) }
    val openSettings: () -> Unit = {
        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                currentRoute = currentRoute,
                onSelect = { route ->
                    scope.launch { drawerState.close() }
                    if (route == Routes.SETTINGS) {
                        navController.navigate(route) { launchSingleTop = true }
                    } else {
                        navController.switchTo(route)
                    }
                },
            )
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = when (gateState) {
                GateState.NoConnection -> Routes.SETUP
                GateState.NeedsLogin -> Routes.LOGIN
                GateState.Ready, GateState.Loading -> Routes.HOME
            },
        ) {
            composable(Routes.SETUP) {
                SetupScreen(onConnectionSaved = { navController.navigate(Routes.LOGIN) { popUpTo(0) } })
            }
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoggedIn = { navController.navigate(Routes.HOME) { popUpTo(0) } },
                    onChangeConnection = { navController.navigate(Routes.SETUP) { popUpTo(0) } },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenMenu = openMenu,
                    onNavigateHome = navigateHome,
                    onOpenSettings = openSettings,
                    onOpenAgent = { agentCommand -> navController.navigate(Routes.runAgent(agentCommand = agentCommand)) },
                    onOpenPath = { pathName -> navController.navigate(Routes.pathDetail(pathName)) },
                    onOpenCommand = { id, pathName -> navController.navigate(Routes.commandDetail(id, pathName)) },
                    onOpenTickets = { navController.navigate(Routes.tickets()) },
                )
            }
            composable(
                route = Routes.RUN_AGENT,
                arguments = listOf(
                    navArgument("agent") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val path = entry.arguments?.getString("path")
                RunAgentScreen(
                    onBack = { navController.popBackStack() },
                    onStarted = { commandId -> navController.navigate(Routes.commandDetail(commandId, path)) { popUpTo(Routes.HOME) } },
                )
            }
            composable(
                route = Routes.PATH_DETAIL,
                arguments = listOf(navArgument("pathName") { type = NavType.StringType }),
            ) {
                PathDetailScreen(
                    onBack = { navController.popBackStack() },
                    onStartAgent = { pathName -> navController.navigate(Routes.runAgent(pathName = pathName)) },
                    onCommandStarted = { commandId, pathName -> navController.navigate(Routes.commandDetail(commandId, pathName)) },
                    onOpenTickets = { pathName -> navController.navigate(Routes.tickets(pathName)) },
                )
            }
            composable(
                route = Routes.COMMAND_DETAIL,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("pathName") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                CommandDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.TICKETS,
                arguments = listOf(
                    navArgument("pathName") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) {
                TicketListScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTicket = { pathName, id -> navController.navigate(Routes.ticketDetail(pathName, id)) },
                )
            }
            composable(
                route = Routes.TICKET_DETAIL,
                arguments = listOf(
                    navArgument("pathName") { type = NavType.StringType },
                    navArgument("id") { type = NavType.IntType },
                ),
            ) {
                TicketDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDisplay = { navController.navigate(Routes.SETTINGS_DISPLAY) },
                    onDisconnected = { navController.navigate(Routes.SETUP) { popUpTo(0) } },
                )
            }
            composable(Routes.SETTINGS_DISPLAY) {
                DisplaySettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
