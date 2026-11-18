package com.example.coverdesign.ui.screens.patient.sensor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * The permission content is tested independently of grants made by other camera tests.
 * The scanner title is checked through the real screen.
 */
class SensorCameraScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sensorScannerTitleIsDisplayed() {
        composeTestRule.setContent {
            SensorCameraScreen()
        }
        composeTestRule.onNodeWithText("Sensor Scanner").assertIsDisplayed()
    }

    @Test
    fun permissionGateHeadlineIsDisplayed() {
        composeTestRule.setContent {
            CameraPermissionGate(onRequest = {})
        }
        composeTestRule.onNodeWithText("Camera access needed").assertIsDisplayed()
    }

    @Test
    fun grantAccessButtonIsDisplayed() {
        composeTestRule.setContent {
            CameraPermissionGate(onRequest = {})
        }
        composeTestRule.onNodeWithText("Grant access").assertIsDisplayed()
    }

    @Test
    fun grantAccessButtonIsEnabled() {
        composeTestRule.setContent {
            CameraPermissionGate(onRequest = {})
        }
        composeTestRule.onNodeWithText("Grant access").assertIsEnabled()
    }

    @Test
    fun permissionExplanationTextIsDisplayed() {
        composeTestRule.setContent {
            CameraPermissionGate(onRequest = {})
        }
        composeTestRule.onNodeWithText(
            "The sensor scanner uses your camera to detect body pose and " +
                "your microphone to optionally record audio alongside video."
        ).assertIsDisplayed()
    }
}
