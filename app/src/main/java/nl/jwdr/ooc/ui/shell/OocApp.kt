package nl.jwdr.ooc.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import nl.jwdr.ooc.R
import nl.jwdr.ooc.ui.containerViewModel
import nl.jwdr.ooc.ui.ecus.EcuListScreen
import nl.jwdr.ooc.ui.ecus.EcuListViewModel
import nl.jwdr.ooc.ui.faultcodes.FaultCodesScreen
import nl.jwdr.ooc.ui.faultcodes.FaultCodesViewModel
import nl.jwdr.ooc.ui.home.HomeScreen
import nl.jwdr.ooc.ui.livedata.LiveDataScreen
import nl.jwdr.ooc.ui.livedata.LiveDataViewModel
import nl.jwdr.ooc.ui.navigation.Route
import nl.jwdr.ooc.ui.settings.SettingsScreen
import nl.jwdr.ooc.transport.ConnectionState

/** Root composable: navigation graph plus the shared shell chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OocApp() {
    val navController = rememberNavController()
    val shellViewModel = containerViewModel { ShellViewModel(it.diagnosticsManager) }
    val connectionState by shellViewModel.connectionState.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val onHome = backStackEntry?.destination?.hasRoute<Route.Home>() ?: true

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(screenTitleRes(backStackEntry?.destination))) },
                navigationIcon = {
                    if (!onHome) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    }
                },
                actions = {
                    ConnectionStatusDot(connectionState)
                    if (onHome) {
                        IconButton(onClick = { navController.navigate(Route.Settings) }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.cd_settings),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (shellViewModel.isSimulated) {
                SimulatedBadge()
            }
            NavHost(navController = navController, startDestination = Route.Home) {
                composable<Route.Home> {
                    HomeScreen(
                        connectionState = connectionState,
                        onToggleConnection = shellViewModel::toggleConnection,
                        onNavigate = { route -> navController.navigate(route) },
                    )
                }
                composable<Route.EcuList> { backStackEntry ->
                    EcuListScreen(
                        viewModel = containerViewModel {
                            EcuListViewModel(it.catalogRepository, it.diagnosticsManager)
                        },
                        autoScan = backStackEntry.toRoute<Route.EcuList>().autoScan,
                        onOpenSettings = { navController.navigate(Route.Settings) },
                        onShowFaults = { ecuName ->
                            navController.navigate(Route.FaultCodes(ecuName))
                        },
                    )
                }
                composable<Route.FaultCodes> { backStackEntry ->
                    FaultCodesScreen(
                        viewModel = containerViewModel {
                            FaultCodesViewModel(
                                it.catalogRepository,
                                it.diagnosticsManager,
                                backStackEntry.toRoute<Route.FaultCodes>().ecuName,
                            )
                        },
                        onOpenEcuList = { navController.navigate(Route.EcuList()) },
                    )
                }
                composable<Route.LiveData> {
                    LiveDataScreen(
                        viewModel = containerViewModel {
                            LiveDataViewModel(
                                it.catalogRepository,
                                it.diagnosticsManager,
                                it.liveDataCsvStore,
                            )
                        },
                        onOpenEcuList = { navController.navigate(Route.EcuList()) },
                    )
                }
                composable<Route.OutputTests> { PlaceholderScreen(issueNumber = 16) }
                composable<Route.Coding> { PlaceholderScreen(issueNumber = 18) }
                composable<Route.Settings> { SettingsScreen() }
            }
        }
    }
}

private fun screenTitleRes(destination: NavDestination?): Int = when {
    destination == null -> R.string.screen_home
    destination.hasRoute<Route.EcuList>() -> R.string.screen_ecu_list
    destination.hasRoute<Route.FaultCodes>() -> R.string.screen_fault_codes
    destination.hasRoute<Route.LiveData>() -> R.string.screen_live_data
    destination.hasRoute<Route.OutputTests>() -> R.string.screen_output_tests
    destination.hasRoute<Route.Coding>() -> R.string.screen_coding
    destination.hasRoute<Route.Settings>() -> R.string.screen_settings
    else -> R.string.screen_home
}

/**
 * Safety rule from the design spec: replay/mock sessions are clearly badged
 * on every screen. Rendered above the NavHost so no destination can lose it.
 */
@Composable
private fun SimulatedBadge() {
    Text(
        text = stringResource(R.string.badge_simulated),
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun ConnectionStatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.Ready -> Color(0xFF2E7D32)
        ConnectionState.Connecting -> Color(0xFFF9A825)
        is ConnectionState.Error -> MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(12.dp)
            .background(color, CircleShape),
    )
}
