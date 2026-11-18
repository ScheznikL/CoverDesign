package com.example.coverdesign.gait

import android.Manifest
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coverdesign.ui.theme.AppTheme
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class GaitPoseLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun poseResultsShowFiveMeasuredLinesAndNeverASpineTile() {
        val samples = (0..2).flatMap { i -> listOf(
            GaitSample(i * 200L, GaitView.FRONT, mapOf(GaitMetric.SHOULDERS to 1.2, GaitMetric.PELVIS to 1.0, GaitMetric.KNEES to 2.0), 0),
            GaitSample(i * 200L + 100, GaitView.BACK, mapOf(GaitMetric.SHOULDERS to 1.5, GaitMetric.PELVIS to .8), 0)) }
        val session = GaitSession("pose-ui-only", 1000, GaitProfile(), samples, 0, 0, 6, 0, true, true,
            protocolVersion = 9, frontValidDurationMs = 6000, backValidDurationMs = 6000,
            measurementMethod = GaitMethod.POSE)
        compose.setContent { AppTheme { GaitResults(session, null, {}) } }
        compose.onNodeWithText("5 of 5 pose lines measured · 5 eligible for trends").assertExists()
        compose.onAllNodesWithText("Shoulders").assertCountEquals(2)
        compose.onAllNodesWithText("Hip landmarks").assertCountEquals(2)
        compose.onAllNodesWithText("Knees").assertCountEquals(1)
        compose.onNodeWithText(GaitMetric.SPINE.label).assertDoesNotExist()
    }

    @Test fun menuKeepsTheTwoLiveMethodsAndVideoAsSeparateActions() {
        var chosen = ""
        compose.setContent {
            AppTheme { GaitMenu(GaitProfile(), onStart = { chosen = "markers" }, onHistory = {},
                onProfile = {}, onInstructions = {}, onBack = {}, onVideo = { chosen = "video" },
                onPoseStart = { chosen = "pose" }) }
        }
        compose.onNodeWithText("Live without markers").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("pose", chosen) }
        compose.onNodeWithText("Live with markers").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("markers", chosen) }
        compose.onNodeWithText("Analyse a video").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("video", chosen) }
    }

    @Test fun backPointChecksNeedFourJointsAndExposeNoColourOrSpineControls() {
        compose.setContent {
            AppTheme { GaitPoseCaptureGuidance(GaitView.BACK,
                requiredMarkers(GaitView.BACK, GaitMethod.POSE).toSet(),
                initialSetup = true, bodyVisible = true, showPointChecks = true) }
        }
        compose.onNodeWithText("4/4 body landmarks tracked").assertExists()
        compose.onNodeWithText("Calibrate on your body").assertDoesNotExist()
        compose.onNodeWithText("Reset all colours").assertDoesNotExist()
        compose.onNodeWithText("C7").assertDoesNotExist()
        compose.onNodeWithText("Coccyx").assertDoesNotExist()
    }

    @Test fun realCameraPoseModeProducesFramesWithoutAnyColourPalette() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        val count = AtomicInteger()
        val wrongMethod = AtomicInteger()
        val colourWork = AtomicInteger()
        compose.setContent {
            AppTheme { GaitCamera(true, onFrame = { frame ->
                count.incrementAndGet()
                if (frame.measurementMethod != GaitMethod.POSE) wrongMethod.incrementAndGet()
                if (frame.markerDiagnostics?.colourCandidates?.isNotEmpty() == true ||
                    frame.markerDiagnostics?.colourPixels?.isNotEmpty() == true) colourWork.incrementAndGet()
            }, onError = {}, modifier = Modifier.fillMaxSize(), measurementMethod = GaitMethod.POSE) }
        }
        compose.waitUntil(45_000) { count.get() >= 3 }
        assertEquals(0, wrongMethod.get())
        assertEquals(0, colourWork.get())
    }

    /** Optional private input is copied only by the local test runner and never bundled. */
    @Test fun realCpuPoseRecognisesFrontAndBackAndProducesNoC7Angle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("gaitPoseReplayPath")
        assumeTrue("Provide the opted-in local walking video", !path.isNullOrBlank())
        val context = instrumentation.targetContext
        val model = PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
            .setRunningMode(RunningMode.VIDEO).build())
        var front = 0
        var back = 0
        var fullFront = 0
        var fullBack = 0
        // Views were checked visually on the corresponding extracted source frames.
        val expected = linkedMapOf(4_064L to GaitView.FRONT, 8_139L to GaitView.BACK,
            12_248L to GaitView.FRONT, 16_325L to GaitView.FRONT, 20_400L to GaitView.BACK,
            24_476L to null, 28_585L to GaitView.FRONT, 32_661L to GaitView.BACK,
            36_737L to GaitView.BACK, 40_813L to GaitView.FRONT)
        try {
            GaitVideoSource(context, Uri.fromFile(File(requireNotNull(path)))).use { source ->
                for ((at, expectedView) in expected) {
                    val bitmap = source.frameAt(at)
                    val inputBitmap = poseInputBitmap(bitmap)
                    val input = BitmapImageBuilder(inputBitmap).build()
                    val frame = try {
                        GaitPoseFrameBuilder.build(model.detectForVideo(input, at + 1).toPoseLandmarks(),
                            bitmap.width, bitmap.height, at)
                    } finally {
                        input.close()
                        if (inputBitmap !== bitmap) inputBitmap.recycle()
                        bitmap.recycle()
                    }
                    val angles = frame.observedView?.let { GaitMath.angles(frame, it) }.orEmpty()
                    assertFalse(angles.containsKey(GaitMetric.SPINE))
                    assertTrue(angles.values.all { it.isFinite() })
                    if (expectedView == null) assertNull("Side-on turn at $at", frame.observedView)
                    if (expectedView == GaitView.FRONT && frame.observedView != null) {
                        assertEquals("Front frame at $at", expectedView, frame.observedView)
                        if (frame.measurementVisible(GaitView.FRONT) && angles.size == 3) front++
                        if (frame.bodyVisible) fullFront++
                    }
                    if (expectedView == GaitView.BACK && frame.observedView != null) {
                        assertEquals("Back frame at $at", expectedView, frame.observedView)
                        if (frame.measurementVisible(GaitView.BACK) && angles.size == 2) back++
                        if (frame.bodyVisible) fullBack++
                    }
                    instrumentation.sendStatus(2, android.os.Bundle().apply {
                        putString("stream", "POSE_REPLAY at=$at view=${frame.observedView} fullBody=${frame.bodyVisible} angleCount=${angles.size}\n")
                    })
                }
            }
        } finally { model.close() }
        assertTrue("At least two visible front frames must yield the three angles; got $front", front >= 2)
        assertTrue("At least two visible rear frames must yield the two angles; got $back", back >= 2)
        assertTrue("Real front footage must support the initial full-body setup", fullFront > 0)
        assertTrue("Real rear footage must support the initial full-body setup", fullBack > 0)
    }
}
