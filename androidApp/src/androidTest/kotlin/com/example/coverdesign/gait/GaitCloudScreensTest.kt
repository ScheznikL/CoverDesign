package com.example.coverdesign.gait

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.coverdesign.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure content tests never connect or grant actual patient access. */
class GaitCloudScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectedDoctorAndRetryActionsAreExplicitAndNoVideoUploadIsOffered() {
        var revoked = false
        var synced = false
        val link = GaitPatientLink("patient-a", "doctor-a", "Test patient", "Test doctor", true)
        compose.setContent {
            AppTheme {
                GaitCloudContent("Waiting for connection.", 2, null, true, link, false, null,
                    "", false, null, {}, {}, { revoked = true }, { synced = true }, {}, {})
            }
        }
        compose.onNodeWithTag("gait-connected-doctor").assertTextEquals("Test doctor")
        compose.onNodeWithTag("gait-share-results").assertIsNotEnabled()
        compose.onNodeWithTag("gait-sync-now").performScrollTo().performClick()
        compose.onNodeWithTag("gait-revoke-sharing").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, synced); assertEquals(true, revoked) }
        compose.onNodeWithText("Upload video").assertDoesNotExist()
    }

    @Test fun inaccessibleSharingStateDoesNotOfferAStaleDoctor() {
        val stale = GaitPatientLink("patient-a", "doctor-a", "Test patient", "Previously connected doctor", true)
        compose.setContent {
            AppTheme {
                GaitCloudContent("Not connected.", 0, null, true, stale, false,
                    "Unable to confirm sharing access.", "", false, null, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("gait-connected-doctor").assertDoesNotExist()
        compose.onNodeWithTag("gait-share-results").assertDoesNotExist()
        compose.onNodeWithText("Retry sharing status").assertExists()
    }

    @Test fun patientListDisplaysOnlyProvidedAuthorizedNamesAndSelection() {
        var selected = ""
        compose.setContent {
            AppTheme {
                GaitPatientListContent(listOf(GaitPatientLink("patient-a", "doctor-a", "Shared test patient", "Doctor", true)),
                    null, true, onSelect = { selected = it })
            }
        }
        compose.onNodeWithText("Shared test patient").performClick()
        compose.runOnIdle { assertEquals("patient-a", selected) }
        compose.onNodeWithText("Maria L.").assertDoesNotExist()
    }

    @Test fun poseReportShowsOnlyItsFiveNumericLinesAndNoInventedSpinalValue() {
        val values = mapOf("FRONT_SHOULDERS" to 1.2, "FRONT_PELVIS" to .8,
            "FRONT_KNEES" to 2.1, "BACK_SHOULDERS" to 1.4, "BACK_PELVIS" to .6,
            "BACK_SPINE" to 99.0)
        val report = GaitCloudReport("patient-a", "report-a", 1000, GaitMethod.POSE,
            "LIVE", 9, true, true, "group-a", values, values.mapValues { 20 },
            values.keys, 20, 5, 6000, 6000)
        compose.setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    GaitDoctorReportsContent("patient-a", listOf(report))
                }
            }
        }
        compose.onNodeWithTag("gait-cloud-value-report-a-FRONT_SHOULDERS").assertTextEquals("1.2°")
        compose.onNodeWithTag("gait-cloud-value-report-a-BACK_PELVIS").assertTextEquals("0.6°")
        compose.onNodeWithTag("gait-cloud-value-report-a-BACK_SPINE").assertDoesNotExist()
        compose.onNodeWithText("99.0°").assertDoesNotExist()
    }
}
