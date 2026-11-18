package com.example.coverdesign.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.coverdesign.domain.model.AppUser
import com.example.coverdesign.domain.model.UserRole
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testUser = AppUser(
        uid = "test-uid",
        email = "user@example.com",
        displayName = "User",
        phone = "",
        role = UserRole.PATIENT,
        createdAt = 0L
    )

    @Test
    fun staticContent_isDisplayed() {
        composeTestRule.setContent {
            ProfileContent(appUser = testUser, onSignOut = {})
        }

        // displayName and email each appear in both the header and the info card —
        // verify at least one instance of each is visible
        composeTestRule.onAllNodesWithText("User")[0].assertIsDisplayed()
        composeTestRule.onAllNodesWithText("user@example.com")[0].assertIsDisplayed()
    }

    @Test
    fun menuItems_areDisplayed() {
        composeTestRule.setContent {
            ProfileContent(appUser = null, onSignOut = {})
        }

        // "Settings" appears as both a section header and a row label, so skip it;
        // the other three row labels are unique
        composeTestRule.onNodeWithText("Notifications").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Help & Support").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Privacy Policy").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun signOut_click_invokesCallback() {
        var signOutCalled = false
        composeTestRule.setContent {
            ProfileContent(appUser = null, onSignOut = { signOutCalled = true })
        }

        composeTestRule.onNodeWithText("Sign Out").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.runOnIdle { assertTrue(signOutCalled) }
    }
}
