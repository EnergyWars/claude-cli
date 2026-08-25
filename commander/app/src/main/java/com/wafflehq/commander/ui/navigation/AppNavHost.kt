package com.wafflehq.commander.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wafflehq.commander.ui.agents.AgentsScreen
import com.wafflehq.commander.ui.collect.CollectScreen
import com.wafflehq.commander.ui.command.CommandDetailScreen
import com.wafflehq.commander.ui.commands.CommandsScreen
import com.wafflehq.commander.ui.downloads.DownloadsScreen
import com.wafflehq.commander.ui.feedback.FeedbackListScreen
import com.wafflehq.commander.ui.history.HistoryScreen
import com.wafflehq.commander.ui.login.LoginScreen
import com.wafflehq.commander.ui.projecthome.ProjectHomeScreen
import com.wafflehq.commander.ui.projectselect.ProjectSelectScreen
import com.wafflehq.commander.ui.run.RunAgentScreen
import com.wafflehq.commander.ui.settings.DisplaySettingsScreen
import com.wafflehq.commander.ui.settings.SettingsScreen
import com.wafflehq.commander.ui.settings.contexts.ContextEditScreen
import com.wafflehq.commander.ui.settings.contexts.ContextsScreen
import com.wafflehq.commander.ui.setup.SetupScreen
import com.wafflehq.commander.ui.theme.AppTheme
import com.wafflehq.commander.ui.tickets.TicketDetailScreen
import com.wafflehq.commander.ui.tickets.TicketListScreen

object Routes {
    const val SETUP = "setup"
    const val LOGIN = "login"
    const val PROJECT_SELECT = "project_select"
    const val PROJECT_HOME = "project_home"
    const val COMMANDS = "commands/{pathName}"
    const val DOWNLOADS = "downloads/{pathName}"
    const val AGENTS = "agents/{pathName}"
    const val AGENT_RUN = "agent_run/{pathName}/{agentCommand}"
    const val COMMAND_DETAIL = "command_detail/{id}?pathName={pathName}"
    const val TICKETS = "tickets/{pathName}"
    const val TICKET_DETAIL = "tickets/{pathName}/{id}"
    const val HISTORY = "history/{pathName}"
    const val FEEDBACK = "feedback"
    const val COLLECT = "collect"
    const val SETTINGS = "settings"
    const val SETTINGS_DISPLAY = "settings_display"
    const val SETTINGS_CONTEXTS = "settings_contexts"
    const val SETTINGS_CONTEXT_NEW = "settings_contexts/edit"
    const val SETTINGS_CONTEXT_EDIT = "settings_contexts/edit/{id}"

    fun commands(pathName: String): String = "commands/${Uri.encode(pathName)}"

    fun downloads(pathName: String): String = "downloads/${Uri.encode(pathName)}"

    fun agents(pathName: String): String = "agents/${Uri.encode(pathName)}"

    fun agentRun(pathName: String, agentCommand: String): String =
        "agent_run/${Uri.encode(pathName)}/${Uri.encode(agentCommand)}"

    fun commandDetail(id: String, pathName: String? = null): String {
        val query = pathName?.let { "?pathName=${Uri.encode(it)}" }.orEmpty()
        return "command_detail/${Uri.encode(id)}$query"
    }

    fun tickets(pathName: String): String = "tickets/${Uri.encode(pathName)}"

    fun ticketDetail(pathName: String, id: Int): String = "tickets/${Uri.encode(pathName)}/$id"

    fun history(pathName: String): String = "history/${Uri.encode(pathName)}"

    fun settingsContextEdit(id: Long): String = "settings_contexts/edit/$id"
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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(gateState) {
        when (val state = gateState) {
            GateState.NeedsLogin -> if (currentRoute != Routes.LOGIN && currentRoute != Routes.SETUP) {
                navController.navigate(Routes.LOGIN) { popUpTo(0) }
            }
            is GateState.Ready -> if (currentRoute == Routes.LOGIN || currentRoute == Routes.SETUP) {
                val target = if (state.hasSelectedProject) Routes.PROJECT_HOME else Routes.PROJECT_SELECT
                navController.navigate(target) { popUpTo(0) }
            }
            GateState.NoConnection, GateState.Loading -> Unit
        }
    }

    val openSettings: () -> Unit = {
        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
    }

    NavHost(
        navController = navController,
        startDestination = when (val state = gateState) {
            GateState.NoConnection -> Routes.SETUP
            GateState.NeedsLogin -> Routes.LOGIN
            is GateState.Ready -> if (state.hasSelectedProject) Routes.PROJECT_HOME else Routes.PROJECT_SELECT
            GateState.Loading -> Routes.PROJECT_HOME
        },
    ) {
        composable(Routes.SETUP) {
            SetupScreen(onConnectionSaved = { navController.navigate(Routes.LOGIN) { popUpTo(0) } })
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {},
                onChangeConnection = { navController.navigate(Routes.SETUP) { popUpTo(0) } },
            )
        }
        composable(Routes.PROJECT_SELECT) {
            ProjectSelectScreen(
                onSelected = { navController.navigate(Routes.PROJECT_HOME) { popUpTo(0) } },
            )
        }
        composable(Routes.PROJECT_HOME) {
            ProjectHomeScreen(
                onOpenCommands = { pathName -> navController.navigate(Routes.commands(pathName)) },
                onOpenDownloads = { pathName -> navController.navigate(Routes.downloads(pathName)) },
                onOpenAgents = { pathName -> navController.navigate(Routes.agents(pathName)) },
                onOpenTickets = { pathName -> navController.navigate(Routes.tickets(pathName)) },
                onOpenHistory = { pathName -> navController.navigate(Routes.history(pathName)) },
                onOpenFeedback = { navController.navigate(Routes.FEEDBACK) },
                onOpenCollect = { navController.navigate(Routes.COLLECT) },
                onOpenSettings = openSettings,
            )
        }
        composable(
            route = Routes.COMMANDS,
            arguments = listOf(navArgument("pathName") { type = NavType.StringType }),
        ) {
            CommandsScreen(
                onBack = { navController.popBackStack() },
                onCommandStarted = { commandId, pathName -> navController.navigate(Routes.commandDetail(commandId, pathName)) },
            )
        }
        composable(
            route = Routes.DOWNLOADS,
            arguments = listOf(navArgument("pathName") { type = NavType.StringType }),
        ) {
            DownloadsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.AGENTS,
            arguments = listOf(navArgument("pathName") { type = NavType.StringType }),
        ) { entry ->
            val pathName = checkNotNull(entry.arguments?.getString("pathName"))
            AgentsScreen(
                onBack = { navController.popBackStack() },
                onOpenAgent = { agentCommand -> navController.navigate(Routes.agentRun(pathName, agentCommand)) },
            )
        }
        composable(
            route = Routes.AGENT_RUN,
            arguments = listOf(
                navArgument("pathName") { type = NavType.StringType },
                navArgument("agentCommand") { type = NavType.StringType },
            ),
        ) { entry ->
            val pathName = checkNotNull(entry.arguments?.getString("pathName"))
            RunAgentScreen(
                onBack = { navController.popBackStack() },
                onStarted = { commandId -> navController.navigate(Routes.commandDetail(commandId, pathName)) { popUpTo(Routes.PROJECT_HOME) } },
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
            arguments = listOf(navArgument("pathName") { type = NavType.StringType }),
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
        ) { entry ->
            val pathName = checkNotNull(entry.arguments?.getString("pathName"))
            TicketDetailScreen(
                onBack = { navController.popBackStack() },
                onCommandStarted = { commandId -> navController.navigate(Routes.commandDetail(commandId, pathName)) },
            )
        }
        composable(
            route = Routes.HISTORY,
            arguments = listOf(navArgument("pathName") { type = NavType.StringType }),
        ) { entry ->
            val pathName = checkNotNull(entry.arguments?.getString("pathName"))
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenCommand = { id -> navController.navigate(Routes.commandDetail(id, pathName)) },
            )
        }
        composable(Routes.FEEDBACK) {
            FeedbackListScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.COLLECT) {
            CollectScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDisplay = { navController.navigate(Routes.SETTINGS_DISPLAY) },
                onOpenContexts = { navController.navigate(Routes.SETTINGS_CONTEXTS) },
                onDisconnected = { navController.navigate(Routes.SETUP) { popUpTo(0) } },
            )
        }
        composable(Routes.SETTINGS_DISPLAY) {
            DisplaySettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_CONTEXTS) {
            ContextsScreen(
                onBack = { navController.popBackStack() },
                onOpenNew = { navController.navigate(Routes.SETTINGS_CONTEXT_NEW) },
                onOpenEdit = { id -> navController.navigate(Routes.settingsContextEdit(id)) },
            )
        }
        composable(Routes.SETTINGS_CONTEXT_NEW) {
            ContextEditScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.SETTINGS_CONTEXT_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) {
            ContextEditScreen(onBack = { navController.popBackStack() })
        }
    }
}
