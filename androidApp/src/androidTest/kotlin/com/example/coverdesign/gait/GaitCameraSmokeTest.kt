package com.example.coverdesign.gait

import android.Manifest
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import com.example.coverdesign.ui.theme.AppTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real CameraX -> bitmap -> HSV + bundled pose detector path, not simulated frames. */
@RunWith(AndroidJUnit4::class)
class GaitCameraSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun cameraProducesFreshFramesAfterLensSwitchesAndLifecycleResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        val frontCamera = mutableStateOf(true)
        val snapshotRequestAtMs = mutableStateOf<Long?>(null)
        val calibration = mutableStateOf<MarkerColorCalibration?>(null)
        val stage = mutableIntStateOf(0)
        val freshFrames = AtomicIntegerArray(5)
        val deliveredFrames = AtomicIntegerArray(5)
        val lastFrameAge = AtomicLongArray(5)
        val lastDimensions = AtomicReference<String?>(null)
        val lastPerformance = AtomicReference<String?>(null)
        val stageStartedAt = AtomicLongArray(5)
        val eligibleSnapshotFrames = AtomicInteger(0)
        val freshSnapshots = AtomicInteger(0)
        val invalidSnapshotCallbacks = AtomicInteger(0)
        val invalidDimensions = AtomicInteger(0)
        val lastError = AtomicReference<String?>(null)
        stageStartedAt.set(0, SystemClock.elapsedRealtime())
        compose.setContent {
            val renderedFront = frontCamera.value
            val renderedStage = stage.intValue
            val renderedSnapshotRequest = snapshotRequestAtMs.value
            AppTheme {
                GaitCaptureLayout(title = "Position your phone", onPause = {},
                    preview = {
                        GaitCamera(frontCamera = renderedFront, onFrame = { frame ->
                            if (frame.width <= 0 || frame.height <= 0 || frame.timestampMs <= 0) invalidDimensions.incrementAndGet()
                            val age = SystemClock.elapsedRealtime() - frame.timestampMs
                            deliveredFrames.incrementAndGet(renderedStage)
                            lastFrameAge.set(renderedStage, age)
                            lastDimensions.set("${frame.width}x${frame.height}")
                            frame.markerDiagnostics?.let { stats ->
                                val perf = "RGB=${stats.conversionMs}, colour=${stats.colourMs}, pose=${stats.poseMs}"
                                lastPerformance.set(perf)
                                if (deliveredFrames.get(renderedStage) <= 5) instrumentation.sendStatus(2, android.os.Bundle().apply {
                                    putString("stream", "CAMERA_PERF stage=$renderedStage ageMs=$age dimensions=${frame.width}x${frame.height} $perf\n")
                                })
                            }
                            if (frame.timestampMs > stageStartedAt.get(renderedStage) && age in 0..1200) {
                                freshFrames.incrementAndGet(renderedStage)
                            }
                            if (renderedSnapshotRequest != null && frame.timestampMs >= renderedSnapshotRequest &&
                                frame.bodyVisible && age in 0..1200) eligibleSnapshotFrames.incrementAndGet()
                        }, onError = { lastError.set(it) }, modifier = Modifier.fillMaxSize().testTag("native-camera"),
                            calibration = calibration.value, detectionView = GaitView.FRONT,
                            colourSnapshotRequestAtMs = renderedSnapshotRequest,
                            onColourSnapshot = snapshot@ { snapshot ->
                                val requestedAt = snapshotRequestAtMs.value ?: return@snapshot
                                val age = SystemClock.elapsedRealtime() - snapshot.timestampMs
                                if (snapshot.timestampMs < requestedAt || age !in 0..1200 ||
                                    snapshot.view != GaitView.FRONT || snapshot.width <= 0 || snapshot.height <= 0 ||
                                    snapshot.argb.size != snapshot.width * snapshot.height ||
                                    Looper.myLooper() != Looper.getMainLooper()) {
                                    invalidSnapshotCallbacks.incrementAndGet()
                                }
                                freshSnapshots.incrementAndGet()
                                snapshotRequestAtMs.value = null
                            })
                    },
                    guidance = { Text("Camera height: about 85 cm. Keep your whole body and feet in frame.") }
                )
            }
        }
        fun awaitFreshFrames(stageId: Int) {
            // Three post-transition frames cannot be satisfied by one late result from the old
            // in-flight inference. Real native inference must succeed again after each transition.
            try {
                compose.waitUntil(timeoutMillis = 45_000) { freshFrames.get(stageId) >= 3 }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("Camera stage $stageId: fresh=${freshFrames.get(stageId)}, delivered=${deliveredFrames.get(stageId)}, ageMs=${lastFrameAge.get(stageId)}, dimensions=${lastDimensions.get()}, processing=${lastPerformance.get()}, error=${lastError.get()}", timeout)
            }
            assertTrue("Stage $stageId did not recover: ${lastError.get()}", freshFrames.get(stageId) >= 3)
        }
        awaitFreshFrames(0)
        val native = compose.onNodeWithTag("native-camera").fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithTag("gait-preview").fetchSemanticsNode().boundsInRoot
        val guidance = compose.onNodeWithTag("gait-guidance").fetchSemanticsNode().boundsInRoot
        assertTrue(native.top >= preview.top && native.bottom <= preview.bottom)
        assertTrue(native.bottom <= guidance.top)

        // Only an actual full-body frame can satisfy a photo request. An emulator room without
        // a person must keep delivering detection frames without fabricating a calibration still.
        compose.runOnIdle {
            stageStartedAt.set(1, SystemClock.elapsedRealtime())
            stage.intValue = 1
            snapshotRequestAtMs.value = SystemClock.elapsedRealtime()
            calibration.value = MarkerColorCalibration(mapOf(
                MarkerColor.SHOULDERS to MarkerColorTarget(65.0, 14.0, .3, .15),
                MarkerColor.PELVIS to MarkerColorTarget(198.0, 14.0, .3, .15),
                MarkerColor.KNEES to MarkerColorTarget(340.0, 14.0, .3, .15)
            ))
        }
        // The lens and composition stay in place: native pose processing must continue while
        // waiting for a body photo, without relying on the next lens switch to restart inference.
        awaitFreshFrames(1)

        compose.runOnIdle {
            stageStartedAt.set(2, SystemClock.elapsedRealtime())
            stage.intValue = 2
            frontCamera.value = false
        }
        awaitFreshFrames(2)
        compose.runOnIdle {
            stageStartedAt.set(3, SystemClock.elapsedRealtime())
            stage.intValue = 3
            frontCamera.value = true
        }
        awaitFreshFrames(3)

        // Keep the same composition and selected lens: this exercises CameraX's lifecycle
        // suspension/rebinding rather than replacing GaitCamera with a new composable.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, compose.activityRule.scenario.state)
        instrumentation.runOnMainSync {
            stageStartedAt.set(4, SystemClock.elapsedRealtime())
            stage.intValue = 4
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
        awaitFreshFrames(4)
        compose.runOnIdle {
            assertEquals(0, invalidSnapshotCallbacks.get())
            if (eligibleSnapshotFrames.get() > 0) {
                assertTrue("A visible full-body frame must fulfil the photo request", freshSnapshots.get() > 0)
            } else {
                assertEquals("No full-body pose means no calibration photo", 0, freshSnapshots.get())
            }
        }
        assertEquals(0, invalidDimensions.get())
    }
}
