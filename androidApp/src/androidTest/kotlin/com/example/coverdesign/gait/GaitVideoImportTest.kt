package com.example.coverdesign.gait

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the shipped importer against caller-owned, temporary local media, not a second replay
 * implementation. No private video, frame, thumbnail or crop is packaged or saved by these tests.
 * The fixture is the annotated 40.524 s front-only walking video supplied through gaitVideoPath.
 */
@RunWith(AndroidJUnit4::class)
class GaitVideoImportTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val calibrationAtMs = 7_317L
    private val frontPapers = linkedMapOf(
        MarkerColor.SHOULDERS to listOf(291 to 527, 412 to 529),
        MarkerColor.PELVIS to listOf(309 to 671, 394 to 663),
        MarkerColor.KNEES to listOf(314 to 826, 388 to 823)
    )

    private fun input(): Uri {
        val path = InstrumentationRegistry.getArguments().getString("gaitVideoPath")
        assumeTrue("Opt-in local video; no private media is an APK asset", !path.isNullOrBlank())
        val file = File(requireNotNull(path))
        assertTrue("Caller supplies and removes an app-readable temporary video", file.isFile)
        return Uri.fromFile(file)
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "VIDEO_IMPORT $message\n")
        })
    }

    private fun calibratedFront(uri: Uri): MarkerColorCalibration = GaitVideoSource(context, uri).use { source ->
        val snapshot = source.snapshotAt(calibrationAtMs, GaitView.FRONT)
        assertEquals(720, snapshot.width)
        assertEquals(1280, snapshot.height)
        val targets = frontPapers.mapValues { (role, centres) ->
            val points = centres.map { (x, y) -> MarkerPoint((x + .5) / 720, (y + .5) / 1280) }
            val sample = MarkerBodyColourSample.capture(snapshot, role, points)
            assertTrue("Real selected $role paper: ${sample.message}", sample.valid)
            requireNotNull(sample.target)
        }
        MarkerColorCalibration().withViewTargets(GaitView.FRONT, targets).also {
            assertTrue(it.readyFor(GaitView.FRONT))
            assertFalse("Front-only input has no fabricated back palette", it.readyFor(GaitView.BACK))
        }
    }

    @Test fun importedFrontWalkingVideoProducesStoredReadyAnglesWithoutInventingBackOrSteps() = runBlocking {
        val uri = input()
        val calibration = calibratedFront(uri)
        val progress = mutableListOf<GaitVideoProgress>()
        val result = analyseGaitVideo(context, uri, GaitProfile(), calibration, setOf(GaitView.FRONT),
            onProgress = { progress += it })

        assertEquals("VIDEO", result.acquisitionSource)
        assertEquals(8, result.protocolVersion)
        assertEquals(setOf(GaitView.FRONT), result.videoViews)
        assertFalse(result.sourceMirrored)
        assertTrue(result.sourceDurationMs in 40_000L..41_000L)
        assertTrue("Production importer must save actual angle samples", result.samples.size >= 3)
        assertTrue("Measured motion must yield useful front time", result.frontValidDurationMs > 0)
        assertEquals(0L, result.backValidDurationMs)
        assertFalse("A front-only record cannot complete the two-view protocol", result.completed)
        assertTrue("Imported camera level cannot be certified by this phone's current sensors", result.manualLevel)
        assertEquals(0, result.frontSteps)
        assertEquals(0, result.backSteps)
        assertTrue(result.frontStepsPartial)
        assertTrue(result.samples.all { sample ->
            sample.view == GaitView.FRONT && sample.stepIndex == 0 && sample.atMs in 0..result.sourceDurationMs &&
                sample.angles.keys == setOf(GaitMetric.SHOULDERS, GaitMetric.PELVIS, GaitMetric.KNEES) &&
                sample.angles.values.all { it.isFinite() }
        })
        assertTrue(result.samples.zipWithNext().all { (first, next) -> first.atMs < next.atMs })
        assertEquals(result.samples.size, result.acceptedFrames)
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.zipWithNext().all { (first, next) -> first.processedFrames <= next.processedFrames })
        assertEquals(progress.last().totalFrames, progress.last().processedFrames)
        assertEquals(1f, progress.last().fraction, .0001f)
        report("source=VIDEO protocol=${result.protocolVersion} samples=${result.samples.size} frontMs=${result.frontValidDurationMs} backMs=${result.backValidDurationMs} observedSteps=${result.frontSteps + result.backSteps} processed=${progress.last().processedFrames} completed=${result.completed}")
    }

    @Test fun nativeSourceUprightSnapshotAndMirrorCorrectionPreservePaperCoordinates() {
        val uri = input()
        GaitVideoSource(context, uri).use { source ->
            assertTrue(source.metadata.durationMs in 40_000L..41_000L)
            assertEquals(720, source.metadata.width)
            assertEquals(1280, source.metadata.height)
            val normal = source.frameAt(calibrationAtMs)
            try {
                assertEquals(720, normal.width)
                assertEquals(1280, normal.height)
                GaitVideoSource(context, uri, mirrored = true).use { mirroredSource ->
                    val mirrored = mirroredSource.frameAt(calibrationAtMs)
                    try {
                        assertEquals(normal.width, mirrored.width)
                        assertEquals(normal.height, mirrored.height)
                        val probes = frontPapers.values.flatten() + listOf(0 to 0, 359 to 640, 719 to 1279)
                        probes.forEach { (x, y) ->
                            assertEquals("Horizontal correction must flip exactly once at $x,$y",
                                normal.getPixel(x, y), mirrored.getPixel(normal.width - 1 - x, y))
                        }
                    } finally { mirrored.recycle() }
                }
            } finally { normal.recycle() }
            val snapshot = source.snapshotAt(calibrationAtMs, GaitView.FRONT)
            assertEquals(snapshot.width * snapshot.height, snapshot.argb.size)
            assertEquals(GaitView.FRONT, snapshot.view)
            frontPapers.forEach { (role, points) -> points.forEach { (x, y) ->
                val pixel = markerHsv(snapshot.argb[y * snapshot.width + x])
                assertTrue("Paper centre must remain chromatic after upright decoding", pixel.saturation >= .25 && pixel.value >= .12)
                val expectedBand = when (role) {
                    MarkerColor.SHOULDERS -> 140.0..180.0
                    MarkerColor.PELVIS -> 320.0..355.0
                    else -> 185.0..215.0
                }
                assertTrue("$role paper must remain at its annotated upright position", pixel.hueDegrees in expectedBand)
            } }
        }
        report("sourceOrientation=720x1280 mirrorCorrection=verified annotatedPapers=6")
    }

    @Test fun cancellingProductionImportDoesNotReturnAPartialResultAndAllowsSourceReuse() = runBlocking {
        val uri = input()
        val calibration = calibratedFront(uri)
        var reachedFrames = false
        var returned: GaitSession? = null
        val worker = launch {
            val workerJob = currentCoroutineContext().job
            returned = analyseGaitVideo(context, uri, GaitProfile(), calibration, setOf(GaitView.FRONT),
                onProgress = {
                    if (it.processedFrames >= 2) {
                        reachedFrames = true
                        workerJob.cancel(CancellationException("Test requests cancellation after real decoding"))
                    }
                })
        }
        worker.join()
        assertTrue("Cancellation must occur during actual analysis", reachedFrames)
        assertTrue(worker.isCancelled)
        assertNull("Cancelled work must not be presented or saved as a finished result", returned)
        GaitVideoSource(context, uri).use { source ->
            val snapshot = source.snapshotAt(calibrationAtMs, GaitView.FRONT)
            assertEquals(720 * 1280, snapshot.argb.size)
        }
        report("cancelledAfterDecode=true returnedResult=false sourceReusable=true")
    }

    @Test fun invalidLocalVideoFailsInsteadOfProducingAnEmptySuccess() {
        val file = File.createTempFile("gait-video-invalid-", ".mp4", context.cacheDir)
        try {
            file.writeText("This is an invalid container, not a video.")
            val failure = runCatching { GaitVideoSource(context, Uri.fromFile(file)).use { it.metadata } }.exceptionOrNull()
            assertNotNull("Invalid media must give the UI an error", failure)
        } finally { assertTrue(file.delete()) }
    }
}
