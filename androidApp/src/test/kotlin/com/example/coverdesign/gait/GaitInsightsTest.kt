package com.example.coverdesign.gait

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class GaitInsightsTest {
    private val frontShoulders = GaitMetricKey(GaitView.FRONT, GaitMetric.SHOULDERS)
    private val backSpine = GaitMetricKey(GaitView.BACK, GaitMetric.SPINE)
    private val utc = ZoneId.of("UTC")
    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    private fun session(
        id: String,
        createdAt: Long = time("2026-11-09T12:00:00Z"),
        values: List<Double> = listOf(1.0, 1.0, 1.0),
        view: GaitView = GaitView.FRONT,
        duration: Long = 6_000,
        protocol: Int = 8,
        profile: GaitProfile = GaitProfile(),
        method: GaitMethod = GaitMethod.MARKERS
    ): GaitSession {
        val metrics = expectedGaitMetrics(view, method)
        return GaitSession(id, createdAt, profile,
            values.mapIndexed { i, value -> GaitSample((i + 1) * 200L, view, metrics.associateWith { value }, 0, 1) },
            0, 0, values.size, 0, true, false, protocolVersion = protocol,
            frontValidDurationMs = if (view == GaitView.FRONT) duration else 0,
            backValidDurationMs = if (view == GaitView.BACK) duration else 0,
            acquisitionSource = "VIDEO", sourceDurationMs = 40_000, videoViews = setOf(view), measurementMethod = method)
    }

    @Test fun overviewDescribesMeasuredRangeAndLargestLineWithoutInventingMissingViews() {
        val original = session("front", values = listOf(1.0, 1.0, 1.0))
        val recording = original.copy(samples = original.samples.map { sample ->
            sample.copy(angles = mapOf(GaitMetric.SHOULDERS to 1.0, GaitMetric.PELVIS to 3.0, GaitMetric.KNEES to 2.0))
        })
        val overview = GaitInsights.overview(recording)
        assertEquals(3, overview.availableMetricCount)
        assertEquals(3, overview.qualifiedMetricCount)
        assertEquals(setOf(GaitView.FRONT), overview.availableViews)
        assertEquals(setOf(GaitView.FRONT), overview.qualifiedViews)
        assertFalse(overview.fullCoverage)
        assertEquals(1.0, overview.minimumTypical!!, .00001)
        assertEquals(3.0, overview.maximumTypical!!, .00001)
        assertEquals(GaitMetricKey(GaitView.FRONT, GaitMetric.PELVIS), overview.largest!!.key)
        assertEquals(3, overview.sampleCount)
        assertEquals(1.0, overview.trackingCoverage!!, .00001)
    }

    @Test fun completeCoverageNeedsAllSixMetricKeysAndActualTargetsNotCompletedFlag() {
        val front = session("front", values = listOf(.5, 1.0, 1.5))
        val rear = session("back", view = GaitView.BACK)
        val both = front.copy(samples = front.samples + rear.samples,
            backValidDurationMs = 6_000, completed = false)
        assertTrue(GaitInsights.overview(both).fullCoverage)
        assertEquals(6, GaitInsights.overview(both).qualifiedMetricCount)
        assertFalse(GaitInsights.overview(both.copy(backValidDurationMs = 5_999, completed = true)).fullCoverage)
        val missingSpine = both.copy(samples = both.samples.map { it.copy(angles = it.angles - GaitMetric.SPINE) })
        assertFalse(GaitInsights.overview(missingSpine).fullCoverage)
        assertEquals(setOf(GaitView.FRONT), GaitInsights.overview(missingSpine).qualifiedViews)
    }

    @Test fun smallNonzeroAndOpposingAnglesStayNumericRatherThanBecomingAHealthCategory() {
        val recording = session("oscillating", values = listOf(-1.0, 1.0, -1.0, 1.0))
        val metric = GaitInsights.overview(recording).metrics.first { it.key == frontShoulders }
        assertEquals(0.0, metric.summary.mean, .00001)
        assertEquals(1.0, metric.summary.meanAbsolute, .00001)
        assertTrue(metric.qualifiesForTrend)
    }

    @Test fun nonfiniteAndUnsupportedMetricValuesCannotCreateOrQualifyData() {
        val recording = session("mixed", values = listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.0, -2.0))
        val overview = GaitInsights.overview(recording)
        assertEquals(2, overview.sampleCount)
        assertEquals(1.5, overview.minimumTypical!!, .00001)
        assertEquals(0, overview.qualifiedMetricCount)
        val empty = recording.copy(samples = listOf(GaitSample(1, GaitView.FRONT,
            mapOf(GaitMetric.SPINE to 4.0, GaitMetric.SHOULDERS to Double.NaN), 0)))
        assertTrue(GaitInsights.overview(empty).metrics.isEmpty())
        assertNull(GaitInsights.overview(empty).largest)
        assertFalse(GaitInsights.qualifies(empty, GaitMetricKey(GaitView.FRONT, GaitMetric.SPINE)))
    }

    @Test fun trackingFractionUsesLongTotalsAndUnknownCoverageIsNull() {
        val recording = session("coverage")
        assertEquals(.5, GaitInsights.overview(recording.copy(acceptedFrames = Int.MAX_VALUE,
            rejectedFrames = Int.MAX_VALUE)).trackingCoverage!!, .00001)
        assertNull(GaitInsights.overview(recording.copy(acceptedFrames = 0, rejectedFrames = 0)).trackingCoverage)
    }

    @Test fun unrepresentableSummaryCannotLeakInfinityIntoOverviewOrDailyChart() {
        val corrupted = session("overflow", values = List(3) { Double.MAX_VALUE })
        assertTrue(GaitInsights.overview(corrupted).metrics.isEmpty())
        val trend = GaitInsights.dailyTrend(listOf(corrupted), corrupted, frontShoulders, utc)
        assertTrue(trend.days.isEmpty())
        assertEquals(1, trend.excludedSessionCount)
    }

    @Test fun dailyValuesGiveEverySessionOneVoteAndKeepSignedDirectionSeparate() {
        val short = session("short", values = List(3) { 1.0 })
        val long = session("long", createdAt = short.createdAt + 1000, values = List(99) { -3.0 })
        val day = GaitInsights.dailyTrend(listOf(short, long), short, frontShoulders, utc).days.single()
        assertEquals(2.0, day.typicalAngle, .00001)
        assertEquals(-1.0, day.signedMean, .00001)
        assertEquals(1.0, day.minSessionAngle, .00001)
        assertEquals(3.0, day.maxSessionAngle, .00001)
        assertEquals(2, day.sessionCount)
        assertEquals(listOf("short", "long"), day.sessionIds)
        assertNull(day.deltaFromPreviousDay)
        assertEquals(0.0, day.deltaFromBaseline, .00001)
    }

    @Test fun dailyChangesUsePreviousRecordedDayAndEarliestDayWithoutFillingMissingDates() {
        val first = session("first", time("2026-11-01T12:00:00Z"), values = List(3) { 5.0 })
        val last = session("last", time("2026-11-05T12:00:00Z"), values = List(3) { 3.0 })
        val days = GaitInsights.dailyTrend(listOf(last, first), last, frontShoulders, utc).days
        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5)), days.map { it.date })
        assertEquals(-2.0, days.last().deltaFromPreviousDay!!, .00001)
        assertEquals(-2.0, days.last().deltaFromBaseline, .00001)
    }

    @Test fun suppliedTimeZoneControlsCalendarDayIncludingDstBoundary() {
        val beforeMidnight = session("utc", time("2026-10-24T22:30:00Z"))
        val afterDst = session("berlin", time("2026-10-25T02:30:00Z"))
        val local = GaitInsights.dailyTrend(listOf(beforeMidnight, afterDst), beforeMidnight, frontShoulders,
            ZoneId.of("Europe/Berlin"))
        assertEquals(LocalDate.of(2026, 10, 25), local.days.single().date)
        assertEquals(2, local.days.single().sessionCount)
        assertEquals(2, GaitInsights.dailyTrend(listOf(beforeMidnight, afterDst), beforeMidnight, frontShoulders, utc).days.size)
    }

    @Test fun compatibleGroupsSeparateAcquisitionProfilesAndProtocolsButNotCorrectedMirrorsOrSelectedViews() {
        val base = session("base")
        val alternatives = listOf(
            base.copy(id = "live", acquisitionSource = "LIVE"),
            base.copy(id = "old", protocolVersion = 7),
            base.copy(id = "level", manualLevel = false),
            base.copy(id = "height", profile = base.profile.copy(heightCm = 180)),
            base.copy(id = "weight", profile = base.profile.copy(weightKg = 80)),
            base.copy(id = "age", profile = base.profile.copy(age = 40)),
            base.copy(id = "sex", profile = base.profile.copy(sex = "Male")),
            base.copy(id = "pass", profile = base.profile.copy(passDurationSeconds = 3)),
            base.copy(id = "target", profile = base.profile.copy(targetDurationSecondsPerView = 9))
        )
        alternatives.forEach { assertFalse(it.id, GaitInsights.compatible(base, it)) }
        val same = base.copy(id = "same", createdAt = base.createdAt + 10, sourceMirrored = true,
            videoViews = GaitView.entries.toSet(), profile = base.profile.copy(passSteps = 8, targetStepsPerView = 30))
        assertTrue(GaitInsights.compatible(base, same))
        val groups = GaitInsights.groups(listOf(base, same) + alternatives)
        assertEquals(10, groups.size)
        assertEquals("same", groups.first().reference.id)
        assertEquals(setOf("base", "same"), groups.first().sessions.map { it.id }.toSet())
    }

    @Test fun partialOtherViewDoesNotExcludeQualifiedFrontAndExclusionCountsRemainExplicit() {
        val good = session("front-only", duration = 13_200)
        val brief = session("brief", duration = 1_000)
        val few = session("few", values = listOf(1.0, 1.0))
        val rearOnly = session("rear", view = GaitView.BACK)
        val wrongSource = good.copy(id = "different", acquisitionSource = "LIVE")
        val trend = GaitInsights.dailyTrend(listOf(good, brief, few, rearOnly, wrongSource), good, frontShoulders, utc)
        assertFalse(good.completed)
        assertEquals(4, trend.compatibleSessionCount)
        assertEquals(1, trend.includedSessionCount)
        assertEquals(3, trend.excludedSessionCount)
        assertEquals(setOf("front-only"), trend.days.single().sessionIds.toSet())
        assertEquals(mapOf(GaitTrendExclusion.VIEW_TARGET_NOT_MET to 1,
            GaitTrendExclusion.TOO_FEW_SAMPLES to 1, GaitTrendExclusion.NO_FINITE_SAMPLES to 1), trend.excludedReasons)
    }

    @Test fun legacyStepsStaySeparateFromTimedFieldsAndCompletionCannotOverrideLowCoverage() {
        val legacy = session("legacy", protocol = 5, duration = 0).copy(frontSteps = 12)
        assertTrue(GaitInsights.qualifies(legacy, frontShoulders))
        assertFalse(GaitInsights.qualifies(legacy.copy(frontSteps = 11, completed = true, frontValidDurationMs = 90_000), frontShoulders))
        val equivalent = legacy.copy(profile = legacy.profile.copy(passDurationSeconds = 4, targetDurationSecondsPerView = 12))
        assertTrue(GaitInsights.compatible(legacy, equivalent))
        assertFalse(GaitInsights.compatible(legacy, legacy.copy(profile = legacy.profile.copy(passSteps = 6))))
        assertFalse(GaitInsights.qualifies(legacy.copy(protocolVersion = 0), frontShoulders))
    }

    @Test fun repeatedSessionIdsNeverDoubleCountAndNewestVersionWinsWithoutMutatingInput() {
        val older = session("same", time("2026-11-01T12:00:00Z"))
        val newer = session("same", time("2026-11-02T12:00:00Z"), List(3) { 4.0 })
        val input = listOf(older, newer, older)
        val before = input.toList()
        val trend = GaitInsights.dailyTrend(input, newer, frontShoulders, utc)
        assertEquals(before, input)
        assertEquals(1, trend.compatibleSessionCount)
        assertEquals(1, trend.days.size)
        assertEquals(4.0, trend.days.single().typicalAngle, .00001)
    }

    @Test fun previousComparableFindsCommonQualifiedMetricWithoutDemandingFullTwoViewCompletion() {
        val current = session("current", time("2026-11-09T12:00:00Z"))
        val old = session("old", current.createdAt - 4000)
        val latestBack = session("back", current.createdAt - 1000, view = GaitView.BACK)
        val poor = session("poor", current.createdAt - 500, duration = 1000)
        val future = session("future", current.createdAt + 1000)
        val self = current.copy(createdAt = current.createdAt - 100)
        val candidates = listOf(future, self, poor, latestBack, old)
        assertEquals("old", GaitInsights.previousComparableSession(candidates, current)!!.id)
        assertEquals("old", GaitInsights.previousComparableSession(candidates, current, frontShoulders)!!.id)
        assertNull(GaitInsights.previousComparableSession(candidates, current, backSpine))
        assertNull(GaitInsights.previousComparableSession(candidates, current.copy(frontValidDurationMs = 1000)))
    }

    @Test fun observedPersonalRangeNeedsThreeQualifiedPriorSessionsAndExcludesFutureSelfAndOtherAcquisitions() {
        val current = session("current", time("2026-11-09T12:00:00Z"), List(3) { 1.1 })
        val previous = listOf(.8, 1.0, 1.4).mapIndexed { index, angle ->
            session("prior-$index", current.createdAt - (index + 1) * 86_400_000L, List(3) { angle })
        }
        assertNull(GaitInsights.personalReference(previous.take(2), current, frontShoulders))
        val distractions = listOf(current.copy(createdAt = current.createdAt - 1000),
            session("future", current.createdAt + 1000, List(3) { 90.0 }),
            session("poor", current.createdAt - 1000, List(3) { 90.0 }, duration = 1000),
            session("different", current.createdAt - 1000, List(3) { 90.0 }).copy(acquisitionSource = "LIVE"))
        val range = requireNotNull(GaitInsights.personalReference(previous + distractions, current, frontShoulders))
        assertEquals(3, range.sessionCount)
        assertEquals(.8, range.minTypical, .00001)
        assertEquals(1.4, range.maxTypical, .00001)
        assertEquals((.8 + 1 + 1.4) / 3, range.meanTypical, .00001)
        assertEquals(previous.map { it.id }.toSet(), range.sessionIds.toSet())
    }

    @Test fun poseCoverageRequiresFiveSupportedLinesWithoutSpine() {
        val front = session("pose-front", method = GaitMethod.POSE, protocol = 9)
        val back = session("pose-back", view = GaitView.BACK, method = GaitMethod.POSE, protocol = 9)
        val both = front.copy(samples = front.samples + back.samples, backValidDurationMs = 6_000)
        val overview = GaitInsights.overview(both)
        assertEquals(5, overview.expectedMetricCount)
        assertEquals(5, overview.availableMetricCount)
        assertEquals(5, overview.qualifiedMetricCount)
        assertTrue(overview.fullCoverage)
        assertEquals(GaitView.entries.toSet(), overview.qualifiedViews)
        assertFalse(GaitInsights.metricKeys(both).contains(backSpine))
        assertFalse(overview.metrics.any { it.key.metric == GaitMetric.SPINE })
        assertEquals(6, GaitInsights.overview(session("markers-default")).expectedMetricCount)
    }

    @Test fun poseCoverageStillNeedsBothRearLinesEnoughSamplesAndActualDuration() {
        val front = session("front", method = GaitMethod.POSE, protocol = 9)
        val back = session("back", view = GaitView.BACK, method = GaitMethod.POSE, protocol = 9)
        val both = front.copy(samples = front.samples + back.samples, backValidDurationMs = 6_000)
        val missingPelvis = both.copy(samples = both.samples.map {
            if (it.view == GaitView.BACK) it.copy(angles = it.angles - GaitMetric.PELVIS) else it
        }, completed = true)
        assertFalse(GaitInsights.overview(missingPelvis).fullCoverage)
        assertEquals(setOf(GaitView.FRONT), GaitInsights.overview(missingPelvis).qualifiedViews)
        assertFalse(GaitInsights.overview(both.copy(backValidDurationMs = 5_999, completed = true)).fullCoverage)
        val sparse = both.copy(samples = front.samples + back.samples.take(2), completed = true)
        assertFalse(GaitInsights.overview(sparse).fullCoverage)
        assertEquals(3, GaitInsights.overview(sparse).qualifiedMetricCount)
    }

    @Test fun malformedPoseSpineValuesNeverBecomeMeasuredLinesOrReferences() {
        val rear = session("pose-rear", view = GaitView.BACK, method = GaitMethod.POSE, protocol = 9)
        val polluted = rear.copy(samples = rear.samples.map {
            it.copy(angles = it.angles + (GaitMetric.SPINE to 89.0))
        })
        val overview = GaitInsights.overview(polluted)
        assertEquals(2, overview.availableMetricCount)
        assertEquals(1.0, overview.maximumTypical!!, .00001)
        assertFalse(GaitInsights.qualifies(polluted, backSpine))
        assertNull(GaitInsights.personalReference(List(3) { polluted.copy(id = "older-$it", createdAt = polluted.createdAt - it - 1) }, polluted, backSpine))
        assertNull(GaitInsights.previousComparableSession(listOf(rear.copy(createdAt = polluted.createdAt - 1)), polluted, backSpine))
        val onlySpine = polluted.copy(samples = polluted.samples.map { it.copy(angles = mapOf(GaitMetric.SPINE to 89.0)) })
        assertTrue(GaitInsights.overview(onlySpine).metrics.isEmpty())
        assertEquals(0, GaitInsights.overview(onlySpine).sampleCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun poseCannotRequestADailySpineTrend() {
        val pose = session("pose", method = GaitMethod.POSE, protocol = 9)
        GaitInsights.dailyTrend(listOf(pose), pose, backSpine, utc)
    }

    @Test fun measurementMethodSeparatesDailyGroupsEvenWithIdenticalProtocolAndSettings() {
        val pose = session("pose", values = List(3) { 1.0 }, method = GaitMethod.POSE, protocol = 9)
        val markers = session("markers", createdAt = pose.createdAt - 1000, values = List(3) { 50.0 }, protocol = 9)
        assertFalse(GaitInsights.compatible(pose, markers))
        assertEquals(2, GaitInsights.groups(listOf(pose, markers)).size)
        val trend = GaitInsights.dailyTrend(listOf(pose, markers), pose, frontShoulders, utc)
        assertEquals(1, trend.compatibleSessionCount)
        assertEquals(listOf("pose"), trend.days.single().sessionIds)
        assertEquals(1.0, trend.days.single().typicalAngle, .00001)
        assertNull(GaitInsights.previousComparableSession(listOf(markers), pose, frontShoulders))
    }

    @Test fun posePersonalRangeExcludesMarkerRecordingsWithOtherwiseIdenticalSettings() {
        val current = session("pose-current", method = GaitMethod.POSE, protocol = 9)
        val olderPose = listOf(.8, 1.0, 1.4).mapIndexed { index, value ->
            session("pose-$index", current.createdAt - (index + 1) * 1000, List(3) { value }, method = GaitMethod.POSE, protocol = 9)
        }
        val markers = List(3) { index ->
            session("markers-$index", current.createdAt - (index + 1) * 500, List(3) { 80.0 }, protocol = 9)
        }
        assertNull(GaitInsights.personalReference(olderPose.take(2) + markers, current, frontShoulders))
        val range = requireNotNull(GaitInsights.personalReference(olderPose + markers, current, frontShoulders))
        assertEquals(3, range.sessionCount)
        assertEquals(.8, range.minTypical, .00001)
        assertEquals(1.4, range.maxTypical, .00001)
        assertEquals(olderPose.map { it.id }.toSet(), range.sessionIds.toSet())
        assertEquals("pose-0", GaitInsights.previousComparableSession(olderPose + markers, current, frontShoulders)?.id)
    }
}
