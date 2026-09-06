package com.falcor.viewer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.falcor.viewer.data.prefs.SecureCredentialStore
import com.falcor.viewer.data.repo.FrigateRepository
import com.falcor.viewer.ui.alerts.AlertDetailScreen
import com.falcor.viewer.ui.alerts.AlertsScreen
import com.falcor.viewer.ui.alerts.AlertsViewModel
import com.falcor.viewer.ui.camera.CameraScreen
import com.falcor.viewer.ui.camera.CameraViewModel
import com.falcor.viewer.ui.home.HomeScreen
import com.falcor.viewer.ui.home.HomeViewModel
import com.falcor.viewer.ui.login.LoginScreen
import com.falcor.viewer.ui.login.LoginViewModel

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val CAMERA = "camera/{cameraName}"
    const val ALERTS = "alerts"
    const val ALERT_DETAIL = "alert/{eventId}"

    fun camera(name: String) = "camera/$name"
    fun alert(id: String) = "alert/$id"
}

@Composable
fun FalcorNavHost(
    repository: FrigateRepository,
    credentialStore: SecureCredentialStore
) {
    val navController = rememberNavController()
    val sessionReady by repository.sessionReady.collectAsState()
    val start = if (sessionReady || repository.hasSavedSession()) Routes.HOME else Routes.LOGIN

    NavHost(navController = navController, startDestination = start) {
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
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(repository))
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
        composable(
            Routes.CAMERA,
            arguments = listOf(navArgument("cameraName") { type = NavType.StringType })
        ) { entry ->
            val name = entry.arguments?.getString("cameraName").orEmpty()
            val vm: CameraViewModel = viewModel(
                key = name,
                factory = CameraViewModel.factory(repository, name)
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
