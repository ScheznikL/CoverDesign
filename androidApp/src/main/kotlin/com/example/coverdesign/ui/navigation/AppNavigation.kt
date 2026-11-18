package com.example.coverdesign.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.coverdesign.domain.model.UserRole
import com.example.coverdesign.domain.repo.FireAuthRepository
import com.example.coverdesign.ui.viewmodel.AppViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppScaffold() {
    val authRepo: FireAuthRepository = koinInject()
    val appViewModel: AppViewModel = koinViewModel()
    val userRole by appViewModel.userRole.collectAsStateWithLifecycle()
    val isResolving by appViewModel.isResolving.collectAsStateWithLifecycle()
    val isAuthenticated = remember { authRepo.getCurrentUserId() != null }

    // Block NavHost until the role is known for authenticated sessions so the
    // startDestination is always stable when NavHost first composes.
    if (isAuthenticated && isResolving) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = remember {
        when {
            !isAuthenticated -> Screen.Auth.route
            userRole == UserRole.DOCTOR -> Screen.ClinicianMain.route
            else -> Screen.PatientMain.route
        }
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isClinicianRoute = currentRoute?.startsWith("clinician") == true

    // Patient screens that suppress the bottom bar
    val patientHiddenRoutes = setOf(
        Screen.ModelPreview.route,
        Screen.ModelPreviewFromOrder.route,
        Screen.OrderSummary.route,
        Screen.Login.route,
        Screen.Register.route,
        Screen.ProductConfigFromCatalog.route,
        Screen.ProductConfigFromOrder.route,
        Screen.NewOrder.route,
        Screen.NewInsoleOrder.route,
        Screen.SelectRole.route,
        Screen.RehabHistory.route,
        Screen.Gait.route,
        Screen.GaitProfile.route,
        Screen.SensorCamera.route
    )

    val hidePatientBottomBar = !isClinicianRoute && currentRoute in patientHiddenRoutes
    val isFullscreenCapture = currentRoute == Screen.Gait.route ||
        currentRoute == Screen.GaitProfile.route || currentRoute == Screen.SensorCamera.route

    val isAuthenticated2 by authRepo.isAuthenticated()
        .collectAsStateWithLifecycle(initialValue = isAuthenticated)

    Scaffold(
        // Each capture screen owns its insets, top bar and exit/unsaved-result handling.
        contentWindowInsets = if (isFullscreenCapture) WindowInsets(0, 0, 0, 0)
            else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            when {
                isClinicianRoute -> ClinicianBottomNavBar(
                    currentRoute = currentRoute,
                    onItemSelected = { item ->
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                        }
                    }
                )
                !hidePatientBottomBar -> AppBottomNavBar(
                    currentRoute = currentRoute,
                    onItemSelected = { item ->
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        LaunchedEffect(isAuthenticated2) {
            if (!isAuthenticated2) {
                val authScreens = setOf(
                    Screen.SelectRole.route,
                    Screen.Login.route,
                    Screen.Register.route,
                    Screen.Auth.route
                )
                val route = navController.currentBackStackEntry?.destination?.route
                if (route != null && route !in authScreens) {
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }

        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding)
        ) {
            authGraph(navController)
            patientGraph(navController)
            clinicianGraph(navController)
        }
    }
}
