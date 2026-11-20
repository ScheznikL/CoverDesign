package com.example.coverdesign.ui.screens.patient.rehab

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import com.example.coverdesign.domain.model.DoctorRecommendation
import com.example.coverdesign.domain.model.RecommendationTag
import com.example.coverdesign.ui.viewmodel.RecommendationsAction
import com.example.coverdesign.ui.viewmodel.RecommendationsUiState
import com.example.coverdesign.ui.viewmodel.RehabMockData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecommendationsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sessions = RehabMockData.generateSessions()
    private val recommendations = RehabMockData.generateRecommendations()

    private fun stateWithData(patientName: String = "Maria L.") = RecommendationsUiState(
        latestSession = sessions.getOrNull(0),
        previousSession = sessions.getOrNull(1),
        patientName = patientName,
        recommendations = recommendations
    )

    @Test
    fun patientNameIsDisplayed() {
        composeTestRule.setContent {
            RecommendationsContent(state = stateWithData("Anna K."), onAction = {})
        }
        composeTestRule.onNodeWithText("Anna K.").assertIsDisplayed()
    }

    @Test
    fun myRehabProgressLabelIsDisplayed() {
        composeTestRule.setContent {
            RecommendationsContent(state = stateWithData(), onAction = {})
        }
        composeTestRule.onNodeWithText("My Rehab Progress").assertIsDisplayed()
    }

    @Test
    fun backButtonDispatchesBackAction() {
        var receivedAction: RecommendationsAction? = null
        composeTestRule.setContent {
            RecommendationsContent(
                state = stateWithData(),
                onAction = { receivedAction = it }
            )
        }
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(RecommendationsAction.Back, receivedAction)
    }

    @Test
    fun progressCoefficientsSectionIsDisplayed() {
        composeTestRule.setContent {
            RecommendationsContent(state = stateWithData(), onAction = {})
        }
        composeTestRule.onNodeWithText("Progress Coefficients").assertIsDisplayed()
    }

    @Test
    fun symmetryMetricCardIsDisplayed() {
        composeTestRule.setContent {
            RecommendationsContent(state = stateWithData(), onAction = {})
        }
        composeTestRule.onNodeWithText("Symmetry Coeff.").assertIsDisplayed()
    }

    @Test
    fun gaitClassificationCardIsDisplayed() {
        composeTestRule.setContent {
            RecommendationsContent(state = stateWithData(), onAction = {})
        }
        composeTestRule.onNodeWithText("Gait Classification").assertIsDisplayed()
    }

    @Test
    fun doctorRecommendationsSectionIsDisplayed() {
        composeTestRule.setContent {
            RecommendationsContent(state = stateWithData(), onAction = {})
        }
        composeTestRule.onNodeWithText("Doctor Recommendations").assertIsDisplayed()
    }

    @Test
    fun insoleBasedTagIsDisplayed() {
        composeTestRule.setContent {
            RecommendationsContent(state = stateWithData(), onAction = {})
        }
        composeTestRule.onNodeWithText("Insole-based").performScrollTo().assertIsDisplayed()
    }

//    @Test
//    fun photogrammetricTagIsDisplayed() {
//        composeTestRule.setContent {
//            RecommendationsContent(state = stateWithData(), onAction = {})
//        }
//        composeTestRule.onNodeWithText("Photogrammetric").assertIsDisplayed()
//    }

//    @Test
//    fun doctorNameIsDisplayedInRecommendationCard() {
//        composeTestRule.setContent {
//            RecommendationsContent(state = stateWithData(), onAction = {})
//        }
//        composeTestRule.onNodeWithText("Dr. Rossi").assertIsDisplayed()
//    }

    @Test
    fun footLoadSectionIsDisplayedWhenPressureDataExists() {
        composeTestRule.setContent {
            RecommendationsContent(state = stateWithData(), onAction = {})
        }
        composeTestRule.onNodeWithText("Foot Load Distribution").assertIsDisplayed()
    }

    @Test
    fun noProgressCoefficientsWhenSessionIsNull() {
        composeTestRule.setContent {
            RecommendationsContent(
                state = RecommendationsUiState(
                    latestSession = null,
                    patientName = "Test User",
                    recommendations = emptyList()
                ),
                onAction = {}
            )
        }
        composeTestRule.onNodeWithText("Progress Coefficients").assertDoesNotExist()
    }

    private fun recs(n: Int) = (1..n).map {
        DoctorRecommendation(
            id = "r$it", doctorName = "Dr. $it", timestampMs = 1000L * it,
            tag = RecommendationTag.INSOLE_BASED, text = "Recommendation number $it"
        )
    }

    @Test
    fun showAllButtonRevealsRecommendationsBeyondTwo() {
        composeTestRule.setContent {
            RecommendationsContent(
                state = RecommendationsUiState(patientName = "P", recommendations = recs(3)),
                onAction = {}
            )
        }
        // Collapsed: only the first two cards and a "Show all (3)" button.
        composeTestRule.onNodeWithText("Recommendation number 3").assertDoesNotExist()
        composeTestRule.onNodeWithText("Show all (3)").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Recommendation number 3").performScrollTo().assertIsDisplayed()
        // Expanded: toggle collapses again.
        composeTestRule.onNodeWithText("Show less").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Recommendation number 3").assertDoesNotExist()
    }

    @Test
    fun noShowAllButtonWithTwoOrFewerRecommendations() {
        composeTestRule.setContent {
            RecommendationsContent(
                state = RecommendationsUiState(patientName = "P", recommendations = recs(2)),
                onAction = {}
            )
        }
        composeTestRule.onNodeWithText("Show all (2)").assertDoesNotExist()
    }

    @Test
    fun noRecommendationsSectionWhenListIsEmpty() {
        composeTestRule.setContent {
            RecommendationsContent(
                state = RecommendationsUiState(
                    latestSession = sessions.first(),
                    patientName = "Test User",
                    recommendations = emptyList()
                ),
                onAction = {}
            )
        }
        composeTestRule.onNodeWithText("Doctor Recommendations").assertDoesNotExist()
    }
}
