package com.example.coverdesign.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.example.coverdesign.gait.GaitDoctorScreen
import com.example.coverdesign.ui.screens.ProfileScreen


fun NavGraphBuilder.clinicianGraph(
    navController: NavHostController
) {
    navigation(
        route = Screen.ClinicianMain.route,
        startDestination = Screen.ClinicianHome.route
    ) {
        composable(Screen.ClinicianHome.route) {
            GaitDoctorScreen()
        }
        composable(Screen.ClinicianProfile.route) {
            ProfileScreen()
        }
        composable(ClinicianNavItem.Patients.route) {
            GaitDoctorScreen()
        }
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
            // Keep old links safe while showing only this doctor's authorized numerical reports.
            GaitDoctorScreen(initialPatientUid = patientId, onBack = { navController.popBackStack() })
        }
    }
}
