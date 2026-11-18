package com.example.coverdesign.gait

import org.junit.Assert.*
import org.junit.Test

class MarkerMatchingTest {
    @Test fun displacedPelvisAndKneesUseTheirOwnMidlines() {
        // A leaning torso puts both pelvis and knee landmarks to the image-right
        // of the shoulders' center. A shared shoulder-side filter rejected these.
        val shoulderCenter = 0.38
        val pelvisAnchors = listOf(MarkerPoint(.59, .5), MarkerPoint(.77, .53))
        val kneeAnchors = listOf(MarkerPoint(.64, .75), MarkerPoint(.80, .77))
        val pelvisMarkers = listOf(MarkerPoint(.60, .49), MarkerPoint(.78, .54))
        val kneeMarkers = listOf(MarkerPoint(.65, .74), MarkerPoint(.81, .78))
        assertTrue(pelvisMarkers.all { it.x > shoulderCenter })
        assertTrue(kneeMarkers.all { it.x > shoulderCenter })
        val matcher = MarkerMatcher(
            pelvisMarkers.map { MarkerBlob(MarkerColor.PELVIS, it, .001) } +
                kneeMarkers.map { MarkerBlob(MarkerColor.KNEES, it, .001) },
            1000, 1500, 1100.0
        )
        val pelvisCenter = pelvisAnchors.map { it.x }.average()
        val kneeCenter = kneeAnchors.map { it.x }.average()
        assertEquals(pelvisMarkers[0], matcher.match(MarkerColor.PELVIS, pelvisAnchors[0], .17, -1, pelvisCenter))
        assertEquals(pelvisMarkers[1], matcher.match(MarkerColor.PELVIS, pelvisAnchors[1], .17, 1, pelvisCenter))
        assertEquals(kneeMarkers[0], matcher.match(MarkerColor.KNEES, kneeAnchors[0], .12, -1, kneeCenter))
        assertEquals(kneeMarkers[1], matcher.match(MarkerColor.KNEES, kneeAnchors[1], .12, 1, kneeCenter))
    }

    @Test fun ambiguityAndWrongSideStillDoNotConfirmMarkers() {
        val anchor = MarkerPoint(.3, .5)
        val matcher = MarkerMatcher(listOf(
            MarkerBlob(MarkerColor.PELVIS, MarkerPoint(.30, .50), .001),
            MarkerBlob(MarkerColor.PELVIS, MarkerPoint(.31, .50), .001)
        ), 1000, 1500, 1000.0)
        assertNull(matcher.match(MarkerColor.PELVIS, anchor, .17, -1, .5))
        assertNull(matcher.match(MarkerColor.PELVIS, anchor, .17, 1, .5))
    }

    @Test fun rearEllipseAddsVerticalLeewayWithoutExpandingSidewaysOrItsCorners() {
        val anchor = MarkerPoint(.5, .5)
        val paper = MarkerPoint(.5, .58)
        val matcher = MarkerMatcher(listOf(MarkerBlob(MarkerColor.COCCYX, paper, .001)), 1000, 1500, 1000.0)
        assertNull(matcher.match(MarkerColor.COCCYX, anchor, .10))
        assertEquals(paper, matcher.matchEllipse(MarkerColor.COCCYX, anchor, .10, .15))
        for (outside in listOf(MarkerPoint(.61, .5), MarkerPoint(.58, .59))) {
            val wrong = MarkerMatcher(listOf(MarkerBlob(MarkerColor.COCCYX, outside, .001)), 1000, 1500, 1000.0)
            assertNull(wrong.matchEllipse(MarkerColor.COCCYX, anchor, .10, .15))
        }
        assertNull(matcher.matchEllipse(MarkerColor.COCCYX, anchor, .10, .15, minY = .59))
    }

    @Test fun tinyKneeFragmentsDoNotDisplaceOrInventTheirStrongerCounterpart() {
        val points = listOf(MarkerPoint(.4, .7), MarkerPoint(.6, .7))
        for (weakSide in 0..1) {
            val blobs = points.mapIndexed { index, point ->
                MarkerBlob(MarkerColor.KNEES, point, if (index == weakSide) .00003 else .001)
            }
            val matcher = MarkerMatcher(blobs, 1000, 1500, 1000.0)
            val filtered = matcher.rejectTinyKneeFragment(points)
            assertNull(filtered[weakSide])
            assertEquals(points[1 - weakSide], filtered[1 - weakSide])
            // A single observed paper is not compared with an imagined opposite paper.
            assertEquals(listOf(null, points[1]), matcher.rejectTinyKneeFragment(listOf(null, points[1])))
        }
    }

    @Test fun normalKneePerspectiveAndBoundarySizeDifferencesRemainAccepted() {
        val points = listOf(MarkerPoint(.4, .7), MarkerPoint(.6, .7))
        for (ratio in listOf(.20, .10, 1.0)) {
            val matcher = MarkerMatcher(listOf(
                MarkerBlob(MarkerColor.KNEES, points[0], .001 * ratio),
                MarkerBlob(MarkerColor.KNEES, points[1], .001)), 1000, 1500, 1000.0)
            assertEquals(points, matcher.rejectTinyKneeFragment(points))
        }
    }
}
