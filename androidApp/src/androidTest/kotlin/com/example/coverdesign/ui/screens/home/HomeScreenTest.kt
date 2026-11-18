package com.example.coverdesign.ui.screens.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.example.coverdesign.domain.model.OrderCategory
import com.example.coverdesign.domain.model.BodyPosition
import com.example.coverdesign.domain.model.GaitAnalysisResult
import com.example.coverdesign.domain.model.GaitSession as ServerGaitSession
import com.example.coverdesign.domain.model.OrderListItem
import com.example.coverdesign.domain.model.OrderStatus
import com.example.coverdesign.ui.screens.patient.home.HomeContent
import com.example.coverdesign.ui.viewmodel.HomeAction
import com.example.coverdesign.ui.viewmodel.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleOrderItem = OrderListItem(
        orderId    = "order_1",
        category   = OrderCategory.PROSTHETIC_COVER,
        createdAt  = 123456789L,
        totalPrice = 100.0,
        status     = OrderStatus.SUBMITTED
    )

    @Test
    fun testNewOrderClick() {
        var actionReceived: HomeAction? = null
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(),
                onAction = { actionReceived = it }
            )
        }

        composeTestRule.onNodeWithText("Create new order").performScrollTo().performClick()
        assertEquals(HomeAction.NewOrder, actionReceived)
    }

    @Test
    fun testEmptyOrdersState() {
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(recentOrders = emptyList()),
                onAction = {}
            )
        }

        composeTestRule.onNodeWithText("No orders yet").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun testEmptyGaitState() {
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(rehabSessions = emptyList()),
                onAction = {}
            )
        }

        composeTestRule.onNodeWithText("No gait measurements yet").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun testRecentOrderClick() {
        var actionReceived: HomeAction? = null
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(recentOrders = listOf(sampleOrderItem)),
                onAction = { actionReceived = it }
            )
        }

        // RecentOrderCard shows category.label — PROSTHETIC_COVER → "Prosthetic Cover"
        composeTestRule.onNodeWithText("Prosthetic Cover").performScrollTo().performClick()
        assertEquals(HomeAction.OpenOrder("order_1"), actionReceived)
    }

    @Test
    fun testSeeAllOrdersClick() {
        var actionReceived: HomeAction? = null
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(recentOrders = listOf(sampleOrderItem)),
                onAction = { actionReceived = it }
            )
        }

        composeTestRule.onNodeWithText("See all").performScrollTo().performClick()
        assertEquals(HomeAction.SeeAllOrders, actionReceived)
    }

    @Test
    fun testViewRehabHistoryClick() {
        var actionReceived: HomeAction? = null
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(),
                onAction = { actionReceived = it }
            )
        }

        composeTestRule.onNodeWithTag("home-server-rehab-history").performScrollTo().performClick()
        assertEquals(HomeAction.OpenRehabHistory, actionReceived)
    }

    @Test
    fun testWelcomeCoachMarkShownForNewUser() {
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(isNewUser = true),
                onAction = {}
            )
        }

        composeTestRule.onNodeWithText("Welcome aboard!").assertIsDisplayed()
    }

    @Test
    fun testWelcomeCoachMarkHiddenForExistingUser() {
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(isNewUser = false),
                onAction = {}
            )
        }

        composeTestRule.onNodeWithText("Welcome aboard!").assertDoesNotExist()
    }

    @Test
    fun testSkipDismissesWelcome() {
        var actionReceived: HomeAction? = null
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(isNewUser = true),
                onAction = { actionReceived = it }
            )
        }

        composeTestRule.onNodeWithText("Skip").performClick()
        assertEquals(HomeAction.DismissWelcome, actionReceived)
    }

    @Test
    fun testStartTourOpensQuickTour() {
        var actionReceived: HomeAction? = null
        composeTestRule.setContent {
            HomeContent(
                state = HomeUiState(isNewUser = true),
                onAction = { actionReceived = it }
            )
        }

        composeTestRule.onNodeWithText("Start tour").performClick()

        // Starting the tour dismisses the coach-mark and shows the first tour step.
        assertEquals(HomeAction.DismissWelcome, actionReceived)
        composeTestRule.onNodeWithText("Create an order").assertIsDisplayed()
    }

    @Test
    fun testRecommendationsClick() {
        var actionReceived: HomeAction? = null
        composeTestRule.setContent {
            HomeContent(state = HomeUiState(), onAction = { actionReceived = it })
        }
        composeTestRule.onNodeWithText("View Recommendations").performScrollTo().performClick()
        assertEquals(HomeAction.OpenRecommendations, actionReceived)
    }

    @Test
    fun testGaitHistoryIsSeparateFromServerRehabHistory() {
        var gaitHistoryOpened = false
        var actionReceived: HomeAction? = null
        composeTestRule.setContent {
            HomeContent(state = HomeUiState(), onAction = { actionReceived = it },
                onGaitHistory = { gaitHistoryOpened = true })
        }
        composeTestRule.onNodeWithTag("home-gait-view-history").performScrollTo().performClick()
        assertTrue(gaitHistoryOpened)
        assertEquals(null, actionReceived)
        composeTestRule.onNodeWithTag("home-server-rehab-history").performScrollTo().performClick()
        assertEquals(HomeAction.OpenRehabHistory, actionReceived)
    }

    @Test
    fun testServerEmptyStatePreservesScanAndInsoleActions() {
        var actionReceived: HomeAction? = null
        composeTestRule.setContent {
            HomeContent(state = HomeUiState(rehabSessions = emptyList()), onAction = { actionReceived = it })
        }
        composeTestRule.onNodeWithText("No scan recorded").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Make a scan").performScrollTo().performClick()
        assertEquals(HomeAction.StartRehabScan, actionReceived)
        composeTestRule.onNodeWithText("No insoles connected").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Order Insoles").performScrollTo().performClick()
        assertEquals(HomeAction.OrderInsoles, actionReceived)
    }

    @Test
    fun testServerLoadingStateDoesNotShowAnInventedSession() {
        composeTestRule.setContent {
            HomeContent(state = HomeUiState(isLoadingRehab = true), onAction = {})
        }
        composeTestRule.onNodeWithTag("home-server-rehab-loading").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Loading rehabilitation data…").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home-server-rehab-summary").assertDoesNotExist()
        composeTestRule.onNodeWithText("Posture").assertDoesNotExist()
    }

    @Test
    fun testServerSessionValuesStaySeparateFromLocalGaitAngles() {
        val serverSession = ServerGaitSession(
            id = "server-session", patientUid = "test-patient", timestamp = 1_000,
            durationSeconds = 8,
            gaitAnalysis = GaitAnalysisResult(
                postureScore = 61f, balanceScore = 72f, symmetryScore = 83f, scoliosisScore = 64f,
                positionSeries = listOf(BodyPosition(0, 1f, -1f), BodyPosition(100, 2f, -2f)),
                abnormalities = emptyList()),
            pressureData = null)
        composeTestRule.setContent {
            HomeContent(state = HomeUiState(rehabSessions = listOf(serverSession)), onAction = {})
        }
        composeTestRule.onNodeWithText("No gait measurements yet").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("home-gait-FRONT-SHOULDERS-angle").assertDoesNotExist()
        composeTestRule.onNodeWithTag("home-server-rehab-summary").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("61%").assertIsDisplayed()
        composeTestRule.onNodeWithText("72%").assertIsDisplayed()
        composeTestRule.onNodeWithText("83%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Body Deviation").assertExists()
    }
}
