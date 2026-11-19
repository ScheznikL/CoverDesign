package com.example.coverdesign.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.example.coverdesign.gait.GaitDoctorScreen
import com.example.coverdesign.ui.screens.ProfileScreen
import com.example.coverdesign.ui.screens.clinician.home.ClinicianHomeScreen
import com.example.coverdesign.ui.screens.clinician.patients.ClinicianPatientsScreen
import com.example.coverdesign.ui.screens.clinician.recommendation.WriteRecommendationScreen


fun NavGraphBuilder.clinicianGraph(
    navController: NavHostController
) {
    navigation(
        route = Screen.ClinicianMain.route,
        startDestination = Screen.ClinicianHome.route
    ) {
        composable(Screen.ClinicianHome.route) {
            ClinicianHomeScreen(
                onPatientClick = { patientId ->
                    navController.navigate(Screen.WriteRecommendation.createRoute(patientId))
                },
                onViewAllPatientsClick = {
                    navController.navigate(ClinicianNavItem.Patients.route)
                },
                onNotificationsClick = {
                    // TODO: clinician notifications screen not yet implemented
                }
            )
        }
        composable(Screen.ClinicianProfile.route) {
            ProfileScreen()
        }
        composable(ClinicianNavItem.Patients.route) {
            ClinicianPatientsScreen(
                onBack = { navController.popBackStack() },
                onPatientClick = { patientId ->
                    navController.navigate(Screen.WriteRecommendation.createRoute(patientId))
                }
            )
        }
        // Reports keeps the gait cloud results screen introduced by the gait-integration PR.
        composable(ClinicianNavItem.Reports.route) {
            GaitDoctorScreen()
        }
        composable(ClinicianNavItem.Settings.route) {
            ProfileScreen()
        }
        composable(
            route = Screen.WriteRecommendation.route,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) { backStackEntry ->
            val patientId = backStackEntry.arguments?.getString("patientId").orEmpty()
            WriteRecommendationScreen(
                patientId = patientId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
