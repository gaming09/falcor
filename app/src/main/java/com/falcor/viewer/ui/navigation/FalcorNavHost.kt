package com.falcor.viewer.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.falcor.viewer.R
import com.falcor.viewer.data.prefs.AppPreferences
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.falcor.viewer.data.repo.FrigateRepository
import com.falcor.viewer.ui.alerts.AlertDetailScreen
import com.falcor.viewer.ui.alerts.AlertsScreen
import com.falcor.viewer.ui.alerts.AlertsViewModel
import com.falcor.viewer.ui.camera.CameraScreen
import com.falcor.viewer.ui.camera.CameraViewModel
import com.falcor.viewer.ui.dashboard.DashboardScreen
import com.falcor.viewer.ui.dashboard.DashboardViewModel
import com.falcor.viewer.ui.home.HomeScreen
import com.falcor.viewer.ui.home.HomeViewModel
import com.falcor.viewer.ui.login.LoginScreen
import com.falcor.viewer.ui.login.LoginViewModel

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val DASHBOARDS = "dashboards"
    const val CAMERA = "camera/{cameraName}"
    const val ALERTS = "alerts"
    const val ALERT_DETAIL = "alert/{eventId}"

    fun camera(name: String) = "camera/$name"
    fun alert(id: String) = "alert/$id"
}

private data class TopTab(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun FalcorNavHost(
    repository: FrigateRepository,
    credentialStore: SecureCredentialStore,
    appPreferences: AppPreferences
) {
    val navController = rememberNavController()
    val sessionReady by repository.sessionReady.collectAsState()
    val start = if (sessionReady || repository.hasSavedSession()) Routes.HOME else Routes.LOGIN
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in setOf(Routes.HOME, Routes.DASHBOARDS, Routes.ALERTS)

    val tabs = listOf(
        TopTab(Routes.HOME, R.string.nav_cameras, Icons.Default.Videocam),
        TopTab(Routes.DASHBOARDS, R.string.nav_dashboards, Icons.Default.Dashboard),
        TopTab(Routes.ALERTS, R.string.nav_alerts, Icons.Default.Notifications)
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.LOGIN) {
                val vm: LoginViewModel = viewModel(
                    factory = LoginViewModel.factory(repository, credentialStore)
                )
                LoginScreen(
                    viewModel = vm,
                    onLoggedIn = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.HOME) {
                val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(repository, appPreferences))
                HomeScreen(
                    viewModel = vm,
                    onOpenCamera = { name -> navController.navigate(Routes.camera(name)) },
                    onOpenAlerts = { navController.navigate(Routes.ALERTS) },
                    onLogout = {
                        repository.logout()
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.DASHBOARDS) {
                val vm: DashboardViewModel = viewModel(
                    factory = DashboardViewModel.factory(repository, appPreferences)
                )
                DashboardScreen(
                    viewModel = vm,
                    onOpenCamera = { name -> navController.navigate(Routes.camera(name)) }
                )
            }
            composable(
                Routes.CAMERA,
                arguments = listOf(navArgument("cameraName") { type = NavType.StringType })
            ) { entry ->
                val name = entry.arguments?.getString("cameraName").orEmpty()
                val vm: CameraViewModel = viewModel(
                    key = name,
                    factory = CameraViewModel.factory(repository, appPreferences, name)
                )
                CameraScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.ALERTS) {
                val vm: AlertsViewModel = viewModel(factory = AlertsViewModel.factory(repository))
                AlertsScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenEvent = { id -> navController.navigate(Routes.alert(id)) },
                    onOpenCamera = { name -> navController.navigate(Routes.camera(name)) }
                )
            }
            composable(
                Routes.ALERT_DETAIL,
                arguments = listOf(navArgument("eventId") { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString("eventId").orEmpty()
                val vm: AlertsViewModel = viewModel(factory = AlertsViewModel.factory(repository))
                AlertDetailScreen(
                    eventId = id,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenCamera = { name ->
                        navController.navigate(Routes.camera(name)) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}
