package com.example.coverdesign.gait

import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coverdesign.ui.theme.AppTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real local video through the production player, frame editor and analysis; no image capture. */
@RunWith(AndroidJUnit4::class)
class GaitVideoImportWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choosingAFrontFrameAndItsSixPapersEnablesAndCompletesLocalAnalysis() {
        val path = InstrumentationRegistry.getArguments().getString("gaitVideoPath")
        assumeTrue("Opt-in caller-owned local video; no private asset in either APK", !path.isNullOrBlank())
        val file = File(requireNotNull(path))
        assertTrue(file.isFile)
        val result = AtomicReference<GaitSession?>(null)
        val finishes = AtomicInteger(0)
        compose.setContent { AppTheme {
            GaitVideoImport(GaitProfile(), onFinished = { result.set(it); finishes.incrementAndGet() },
                onBack = {}, initialUri = Uri.fromFile(file))
        } }
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("video-timeline").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("video-analyse").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("video-views-front").performScrollTo().performClick()
        compose.onNodeWithTag("video-timeline").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { action -> assertTrue(action(7.317f)) }
        compose.waitUntil(timeoutMillis = 30_000) {
            val nodes = compose.onAllNodesWithTag("video-calibrate-front").fetchSemanticsNodes()
            nodes.size == 1 && !nodes.single().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("video-calibrate-front").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("body-colour-viewport").fetchSemanticsNodes().isNotEmpty()
        }
        // Pixel-centred points on the original upright 720 x 1280 video, independently mapped
        // to the editor's FIT viewport. They are actual papers, never estimated pose anchors.
        val papers = listOf(291 to 527, 412 to 529, 309 to 671, 394 to 663, 314 to 826, 388 to 823)
        papers.forEach { (x, y) ->
            val viewport = compose.onNodeWithTag("body-colour-viewport")
            val bounds = viewport.fetchSemanticsNode().boundsInRoot
            val scale = min(bounds.width / 720, bounds.height / 1280)
            val left = (bounds.width - 720 * scale) / 2
            val top = (bounds.height - 1280 * scale) / 2
            viewport.performTouchInput { click(Offset(left + (x + .5f) * scale, top + (y + .5f) * scale)) }
        }
        compose.onNodeWithTag("body-apply").assertIsEnabled().performClick()
        compose.onNodeWithTag("video-analyse").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("video-camera-checked").performScrollTo().performClick()
        compose.onNodeWithTag("video-analyse").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 120_000) {
            result.get() != null || compose.onAllNodesWithTag("video-error").fetchSemanticsNodes().isNotEmpty()
        }
        val error = compose.onAllNodesWithTag("video-error").fetchSemanticsNodes().firstOrNull()?.let { node ->
            if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString()
            else "Video error without text"
        }
        val analysed = result.get()
        assertNotNull("Production workflow should return measured angles, error=$error", analysed)
        val session = requireNotNull(analysed)
        assertEquals(1, finishes.get())
        assertEquals("VIDEO", session.acquisitionSource)
        assertEquals(setOf(GaitView.FRONT), session.videoViews)
        assertTrue(session.samples.size >= 3)
        assertTrue(session.frontValidDurationMs > 0)
        assertEquals(0L, session.backValidDurationMs)
        assertEquals(0, session.frontSteps + session.backSteps)
        assertFalse(session.completed)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "VIDEO_IMPORT_UI frameSelection=true paperCentres=6 manualLevel=true source=VIDEO samples=${session.samples.size} frontMs=${session.frontValidDurationMs} backMs=${session.backValidDurationMs}\n")
        })
    }
}
