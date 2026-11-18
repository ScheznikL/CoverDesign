package com.example.coverdesign.ui.navigation

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavGraph
import androidx.navigation.compose.rememberNavController
import androidx.navigation.createGraph
import com.example.coverdesign.ui.screens.ProfileContent
import com.example.coverdesign.ui.screens.patient.home.HomeContent
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.viewmodel.HomeUiState
import com.example.coverdesign.ui.viewmodel.RehabMockData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class GaitEntryIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun patientHomeOpensLocalAnalysisSeparatelyFromOriginalRecording() {
        var analysisOpened = false
        var recordingOpened = false
        compose.setContent {
            AppTheme {
                HomeContent(
                    state = HomeUiState(), onAction = {},
                    onGaitAnalysis = { analysisOpened = true },
                    onRecordVideo = { recordingOpened = true }
                )
            }
        }
        compose.onNodeWithText("Gait analysis").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(analysisOpened)
            assertEquals(false, recordingOpened)
        }
        compose.onNodeWithText("Record video").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(recordingOpened) }
    }

    @Test
    fun gaitTabTargetsLocalAnalysis() {
        var selectedRoute: String? = null
        compose.setContent {
            AppTheme {
                AppBottomNavBar(Screen.Home.route) { selectedRoute = it.route }
            }
        }
        compose.onNodeWithText("Gait").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.Gait.route, selectedRoute) }
    }

    @Test
    fun patientAndClinicianProfilesHaveDistinctDestinations() {
        var registeredGraph: NavGraph? = null
        compose.setContent {
            val navController = rememberNavController()
            val graph = remember(navController) {
                navController.createGraph(startDestination = Screen.PatientMain.route) {
                    patientGraph(navController)
                    clinicianGraph(navController)
                }
            }
            SideEffect { registeredGraph = graph }
        }
        compose.runOnIdle {
            val graph = requireNotNull(registeredGraph)
            val patient = graph.findNode(Screen.PatientMain.route) as NavGraph
            val clinician = graph.findNode(Screen.ClinicianMain.route) as NavGraph
            assertEquals(Screen.Home.route, patient.findNode(patient.startDestinationId)?.route)
            assertNotNull(patient.findNode(Screen.Gait.route))
            assertNotNull(patient.findNode(Screen.GaitProfile.route))
            assertNotNull(patient.findNode(Screen.Profile.route))
            assertNull(patient.findNode(Screen.ClinicianProfile.route))
            assertNotNull(clinician.findNode(Screen.ClinicianProfile.route))
            assertNull(clinician.findNode(Screen.Profile.route))
        }
    }

    @Test
    fun patientProfileExposesCharacteristicsEditor() {
        var opened = false
        compose.setContent {
            AppTheme {
                ProfileContent(appUser = null, onSignOut = {}, onGaitProfile = { opened = true })
            }
        }
        compose.onNodeWithText("Gait analysis profile").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun profileWithoutPatientActionDoesNotExposeGaitEditor() {
        compose.setContent {
            AppTheme { ProfileContent(appUser = null, onSignOut = {}) }
        }
        compose.onNodeWithText("Gait analysis profile").assertDoesNotExist()
    }

    @Test
    fun serverRehabResultsDoNotBecomeLocalGaitMeasurements() {
        compose.setContent {
            AppTheme {
                HomeContent(
                    state = HomeUiState(rehabSessions = RehabMockData.generateSessions()),
                    onAction = {}
                )
            }
        }
        compose.onNodeWithText("No gait measurements yet").performScrollTo().assertIsDisplayed()
        // Server rehabilitation data and locally measured gait reports use separate models.
        compose.onNodeWithText("Latest saved analysis").assertDoesNotExist()
    }
}
