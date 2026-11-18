package com.example.coverdesign.gait

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class MarkerColor { SHOULDERS, PELVIS, KNEES, C7, COCCYX }

/** Shadow and white-balance allowance is bounded by colour separation, never learned from tracking. */
internal enum class MarkerLightingTolerance { STANDARD, VARIABLE }

internal data class MarkerColorTarget(
    val hueDegrees: Double,
    val hueToleranceDegrees: Double,
    val minSaturation: Double,
    val minValue: Double,
    val minHueToleranceDegrees: Double = 6.0
) {
    internal fun isValid() = hueDegrees.isFinite() && hueDegrees in 0.0..<360.0 &&
        hueToleranceDegrees.isFinite() && hueToleranceDegrees in 3.0..30.0 &&
        minSaturation.isFinite() && minSaturation in .20..1.0 &&
        minValue.isFinite() && minValue in .08..1.0 && minHueToleranceDegrees.isFinite() &&
        minHueToleranceDegrees in 3.0..hueToleranceDegrees
}

/** Snapshot all input maps: a camera worker never observes an in-place UI edit. */
internal class MarkerColorCalibration(
    targets: Map<MarkerColor, MarkerColorTarget> = emptyMap(),
    viewTargets: Map<GaitView, Map<MarkerColor, MarkerColorTarget>> = emptyMap(),
    val lightingTolerance: MarkerLightingTolerance = MarkerLightingTolerance.VARIABLE
) {
    private val requestedTargets = targets.toMap()
    private val requestedViewTargets = viewTargets.mapValues { (_, targets) -> targets.toMap() }
        .filterValues { it.isNotEmpty() }
    internal val hasCustomTargets = requestedTargets.isNotEmpty() || requestedViewTargets.isNotEmpty()
    private fun lightingTargets(targets: Map<MarkerColor, MarkerColorTarget>) = targets.mapValues { (_, target) ->
        if (lightingTolerance == MarkerLightingTolerance.STANDARD || !target.isValid()) target
        else target.copy(
            hueToleranceDegrees = max(target.hueToleranceDegrees, (target.hueToleranceDegrees + 4.0).coerceAtMost(28.0)),
            minSaturation = min(target.minSaturation, (target.minSaturation * .80).coerceAtLeast(.25)),
            minValue = (target.minValue * .60).coerceAtLeast(.08)
        )
    }
    private val globalPalette = negotiatePalette(lightingTargets(requestedTargets), MarkerColor.entries.toSet())
    private val frontPalette = negotiatePalette(lightingTargets(requestedTargets + requestedViewTargets[GaitView.FRONT].orEmpty()),
        colorsForCalibrationView(GaitView.FRONT))
    private val backPalette = negotiatePalette(lightingTargets(requestedTargets + requestedViewTargets[GaitView.BACK].orEmpty()),
        colorsForCalibrationView(GaitView.BACK))
    val targets: Map<MarkerColor, MarkerColorTarget> = globalPalette.targets
    internal val paletteProblem: String? = globalPalette.conflict
    val missingColors: Set<MarkerColor> = MarkerColor.entries.filter { it !in targets }.toSet()
    val readyForTracking: Boolean = missingColors.isEmpty() && paletteProblem == null
    val problem: String? = paletteProblem ?: if (missingColors.isNotEmpty())
        "Capture all five marker groups before analysis." else null
    private val frontMissing = frontCalibrationColors.filter { it !in frontPalette.targets }.toSet()
    private val backMissing = backCalibrationColors.filter { it !in backPalette.targets }.toSet()
    private val frontReady = frontMissing.isEmpty() && frontPalette.conflict == null
    private val backReady = backMissing.isEmpty() && backPalette.conflict == null
    private val globalResolved = resolveTargets(globalPalette, readyForTracking)
    private val frontResolved = resolveTargets(frontPalette, frontReady)
    private val backResolved = resolveTargets(backPalette, backReady)

    fun missingColorsFor(view: GaitView): Set<MarkerColor> = if (view == GaitView.FRONT) frontMissing else backMissing
    fun readyFor(view: GaitView): Boolean = if (view == GaitView.FRONT) frontReady else backReady
    fun targetsFor(view: GaitView): Map<MarkerColor, MarkerColorTarget> = paletteFor(view).targets
    fun problemFor(view: GaitView): String? = conflictFor(view) ?: if (missingColorsFor(view).isNotEmpty())
        "Capture every marker group used in this view before analysis." else null
    internal fun conflictFor(view: GaitView): String? = paletteFor(view).conflict
    private fun paletteFor(view: GaitView) = if (view == GaitView.FRONT) frontPalette else backPalette
    private fun resolveTargets(palette: NegotiatedPalette, ready: Boolean): Array<MarkerColorTarget?> =
        Array(MarkerColor.entries.size) { index -> palette.targets[MarkerColor.entries[index]].takeIf { ready } }

    internal fun targetAt(index: Int, view: GaitView? = null): MarkerColorTarget? = when (view) {
        GaitView.FRONT -> frontResolved[index]
        GaitView.BACK -> backResolved[index]
        null -> globalResolved[index]
    }
    /** A new close-paper sample explicitly replaces that colour in both views. */
    fun withTarget(color: MarkerColor, target: MarkerColorTarget) = MarkerColorCalibration(
        requestedTargets + (color to target), requestedViewTargets.mapValues { (_, targets) -> targets - color }, lightingTolerance)

    /** Replace one complete view atomically; the other view keeps its own observed lighting. */
    fun withViewTargets(view: GaitView, targets: Map<MarkerColor, MarkerColorTarget>) =
        MarkerColorCalibration(requestedTargets, requestedViewTargets + (view to targets), lightingTolerance)

    /** Rebuild from the captured targets, so changing modes never compounds the allowance. */
    fun withLightingTolerance(tolerance: MarkerLightingTolerance) =
        MarkerColorCalibration(requestedTargets, requestedViewTargets, tolerance)

    override fun equals(other: Any?) = other is MarkerColorCalibration &&
        requestedTargets == other.requestedTargets && requestedViewTargets == other.requestedViewTargets &&
        lightingTolerance == other.lightingTolerance
    override fun hashCode() = 31 * (31 * requestedTargets.hashCode() + requestedViewTargets.hashCode()) + lightingTolerance.hashCode()
}

private val frontCalibrationColors = setOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS, MarkerColor.KNEES)
private val backCalibrationColors = setOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS, MarkerColor.C7, MarkerColor.COCCYX)
private fun colorsForCalibrationView(view: GaitView): Set<MarkerColor> =
    if (view == GaitView.FRONT) frontCalibrationColors else backCalibrationColors

private data class NegotiatedPalette(val targets: Map<MarkerColor, MarkerColorTarget>, val conflict: String?)

private fun negotiatePalette(requested: Map<MarkerColor, MarkerColorTarget>, allowed: Set<MarkerColor>): NegotiatedPalette {
    val applicable = requested.filterKeys { it in allowed }
    val negotiated = applicable.filterValues { it.isValid() }.toMutableMap()
    var conflict: String? = if (applicable.values.any { !it.isValid() })
        "A colour sample is invalid. Capture it again or reset colour setup." else null
    val colors = negotiated.keys.toList()
    for (i in colors.indices) for (j in i + 1 until colors.size) {
        val first = applicable.getValue(colors[i])
        val second = applicable.getValue(colors[j])
        val availableExtra = markerHueDistance(first.hueDegrees, second.hueDegrees) - 2.0 -
            first.minHueToleranceDegrees - second.minHueToleranceDegrees
        if (availableExtra < -1e-9) {
            conflict = "The ${roleName(colors[i])} and ${roleName(colors[j])} samples are too similar. Use more distinct marker colours."
            continue
        }
        val firstExtra = first.hueToleranceDegrees - first.minHueToleranceDegrees
        val secondExtra = second.hueToleranceDegrees - second.minHueToleranceDegrees
        val wantedExtra = firstExtra + secondExtra
        val fraction = if (wantedExtra == 0.0) 0.0 else (max(0.0, availableExtra) / wantedExtra).coerceAtMost(1.0)
        val firstLimit = first.minHueToleranceDegrees + firstExtra * fraction
        val secondLimit = second.minHueToleranceDegrees + secondExtra * fraction
        negotiated[colors[i]] = negotiated.getValue(colors[i]).let {
            it.copy(hueToleranceDegrees = min(it.hueToleranceDegrees, firstLimit))
        }
        negotiated[colors[j]] = negotiated.getValue(colors[j]).let {
            it.copy(hueToleranceDegrees = min(it.hueToleranceDegrees, secondLimit))
        }
    }
    return NegotiatedPalette(negotiated.toMap(), conflict)
}

private fun roleName(color: MarkerColor): String = when (color) {
    MarkerColor.SHOULDERS -> "shoulder"
    MarkerColor.PELVIS -> "pelvis"
    MarkerColor.KNEES -> "knee"
    MarkerColor.C7 -> "C7"
    MarkerColor.COCCYX -> "coccyx"
}

internal data class MarkerColorSampleResult(
    val color: MarkerColor,
    val target: MarkerColorTarget?,
    val argb: Int,
    val message: String
) {
    val valid: Boolean get() = target != null
}

internal data class MarkerHsv(val hueDegrees: Double, val saturation: Double, val value: Double)

private inline fun <T> withMarkerHsv(pixel: Int, block: (Double, Double, Double) -> T): T {
    val r = ((pixel shr 16) and 255) / 255.0
    val g = ((pixel shr 8) and 255) / 255.0
    val b = (pixel and 255) / 255.0
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val delta = high - low
    if (high == 0.0 || delta == 0.0) return block(0.0, 0.0, high)
    val hue = when (high) {
        r -> (60.0 * ((g - b) / delta) + 360.0) % 360.0
        g -> 60.0 * ((b - r) / delta + 2)
        else -> 60.0 * ((r - g) / delta + 4)
    }
    return block(hue, delta / high, high)
}

internal fun markerHsv(pixel: Int): MarkerHsv = withMarkerHsv(pixel) { h, s, v -> MarkerHsv(h, s, v) }

internal fun markerHueDistance(a: Double, b: Double): Double {
    val distance = abs(a - b) % 360.0
    return min(distance, 360.0 - distance)
}

/** Default bands stay separate. Only shoulders gain lime and a shadow allowance. */
internal object MarkerColorClassifier {
    private val defaults = arrayOf(
        MarkerColorTarget(131.5, 53.5, .40, .18), // SHOULDERS H 78..185
        MarkerColorTarget(222.5, 27.5, .55, .32), // PELVIS H 195..250
        MarkerColorTarget(305.0, 30.0, .55, .32), // KNEES H 275..335
        MarkerColorTarget(60.0, 15.0, .55, .32), // C7 H 45..75
        MarkerColorTarget(25.0, 13.0, .55, .32) // COCCYX H 12..38
    )
    internal fun defaultTarget(color: MarkerColor): MarkerColorTarget = defaults[color.ordinal]

    /** Resolve view readiness and every target once, outside the camera's pixel loop. */
    fun forFrame(calibration: MarkerColorCalibration? = null, view: GaitView? = null): PreparedMarkerClassifier =
        PreparedMarkerClassifier.create(calibration, view)

    fun classify(pixel: Int, calibration: MarkerColorCalibration? = null, view: GaitView? = null): MarkerColor? =
        withMarkerHsv(pixel) { hue, saturation, value -> classify(hue, saturation, value, calibration, view) }

    private fun classify(hue: Double, saturation: Double, value: Double, calibration: MarkerColorCalibration?, view: GaitView?): MarkerColor? {
        if (calibration == null || !calibration.hasCustomTargets) {
            // The per-pixel default path creates neither HSV nor target objects.
            if (hue in 78.0..185.0 && saturation >= .40 && value >= .18) return MarkerColor.SHOULDERS
            if (saturation < .55 || value < .32) return null
            return when (hue) {
                in 195.0..250.0 -> MarkerColor.PELVIS
                in 275.0..335.0 -> MarkerColor.KNEES
                in 45.0..75.0 -> MarkerColor.C7
                in 12.0..38.0 -> MarkerColor.COCCYX
                else -> null
            }
        }
        if (view == null && !calibration.readyForTracking || view != null && !calibration.readyFor(view)) return null
        var found = -1
        var index = 0
        while (index < MarkerColor.entries.size) {
            val target = calibration.targetAt(index, view)
            if (target != null && saturation >= target.minSaturation && value >= target.minValue) {
                val distance = abs(hue - target.hueDegrees)
                val circularDistance = if (distance > 180.0) 360.0 - distance else distance
                if (circularDistance <= target.hueToleranceDegrees) {
                    if (found >= 0) return null
                    found = index
                }
            }
            index++
        }
        return if (found >= 0) MarkerColor.entries[found] else null
    }
}

/** Primitive frame-local targets. Code 0 is unclassified; other codes are colour ordinal + 1. */
internal class PreparedMarkerClassifier private constructor(
    private val defaults: Boolean,
    private val codes: IntArray,
    private val hues: DoubleArray,
    private val tolerances: DoubleArray,
    private val saturations: DoubleArray,
    private val values: DoubleArray
) {
    val hasTargets: Boolean = defaults || codes.isNotEmpty()
    private val minimumSaturation = if (defaults) .40 else saturations.minOrNull() ?: 1.0
    private val minimumValue = if (defaults) .18 else values.minOrNull() ?: 1.0

    fun classifyCode(pixel: Int): Int {
        if (!hasTargets) return 0
        // Keep the reference HSV arithmetic exactly: integer shortcuts change rounding at gates.
        val r = ((pixel shr 16) and 255) / 255.0
        val g = ((pixel shr 8) and 255) / 255.0
        val b = (pixel and 255) / 255.0
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val delta = high - low
        if (high == 0.0 || delta == 0.0) return 0
        val saturation = delta / high
        // A pixel failing these shared floors cannot match any active target. Avoid hue work.
        if (saturation < minimumSaturation || high < minimumValue) return 0
        val hue = when (high) {
            r -> (60.0 * ((g - b) / delta) + 360.0) % 360.0
            g -> 60.0 * ((b - r) / delta + 2)
            else -> 60.0 * ((r - g) / delta + 4)
        }
        if (defaults) {
            // The exact legacy bands, in MarkerColor ordinal order; no target negotiation here.
            if (hue in 78.0..185.0 && saturation >= .40 && high >= .18) return 1
            if (saturation < .55 || high < .32) return 0
            return when (hue) {
                in 195.0..250.0 -> 2
                in 275.0..335.0 -> 3
                in 45.0..75.0 -> 4
                in 12.0..38.0 -> 5
                else -> 0
            }
        }
        var found = 0
        var index = 0
        while (index < codes.size) {
            if (saturation >= saturations[index] && high >= values[index]) {
                val distance = abs(hue - hues[index])
                val circularDistance = if (distance > 180.0) 360.0 - distance else distance
                if (circularDistance <= tolerances[index]) {
                    if (found != 0) return 0
                    found = codes[index]
                }
            }
            index++
        }
        return found
    }

    companion object {
        private val DEFAULT = PreparedMarkerClassifier(true, intArrayOf(), doubleArrayOf(), doubleArrayOf(), doubleArrayOf(), doubleArrayOf())
        private val EMPTY = PreparedMarkerClassifier(false, intArrayOf(), doubleArrayOf(), doubleArrayOf(), doubleArrayOf(), doubleArrayOf())

        fun create(calibration: MarkerColorCalibration?, view: GaitView?): PreparedMarkerClassifier {
            if (calibration == null || !calibration.hasCustomTargets) return DEFAULT
            if (view == null && !calibration.readyForTracking || view != null && !calibration.readyFor(view)) return EMPTY
            val targets = MarkerColor.entries.mapNotNull { color ->
                calibration.targetAt(color.ordinal, view)?.let { color to it }
            }
            return PreparedMarkerClassifier(false, targets.map { it.first.ordinal + 1 }.toIntArray(),
                targets.map { it.second.hueDegrees }.toDoubleArray(),
                targets.map { it.second.hueToleranceDegrees }.toDoubleArray(),
                targets.map { it.second.minSaturation }.toDoubleArray(),
                targets.map { it.second.minValue }.toDoubleArray())
        }
    }
}

/** Learns a real paper patch, not a body's estimated marker positions. */
internal object MarkerColorSample {
    const val ROI_FRACTION = .18

    fun capture(
        argb: IntArray,
        width: Int,
        height: Int,
        color: MarkerColor,
        existing: MarkerColorCalibration? = null
    ): MarkerColorSampleResult {
        fun invalid(message: String, swatch: Int = 0xff808080.toInt()) = MarkerColorSampleResult(color, null, swatch, message)
        if (width < 16 || height < 16 || width.toLong() * height != argb.size.toLong()) {
            return invalid("Camera frame is unavailable. Wait for a clear live image.")
        }
        val side = (min(width, height) * ROI_FRACTION).toInt().coerceAtLeast(3)
        val x0 = (width - side) / 2
        val y0 = (height - side) / 2
        val pixels = ArrayList<Pair<Int, MarkerHsv>>(side * side)
        for (y in y0 until y0 + side) for (x in x0 until x0 + side) {
            val pixel = argb[y * width + x]
            val hsv = markerHsv(pixel)
            if (hsv.saturation >= .25 && hsv.value >= .12) pixels += pixel to hsv
        }
        if (pixels.size < side * side * .85) {
            return invalid("Fill the centre box with the coloured paper. Avoid black, grey, glare and deep shadow.")
        }
        fun percentile(values: List<Double>, fraction: Double): Double {
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * fraction).toInt()]
        }
        val saturation = pixels.map { it.second.saturation }
        val value = pixels.map { it.second.value }
        if (percentile(saturation, .5) < .32 || percentile(value, .5) < .16) {
            return invalid("The sample is too pale or dark. Use saturated paper and more even light.")
        }
        val sine = pixels.sumOf { sin(Math.toRadians(it.second.hueDegrees)) }
        val cosine = pixels.sumOf { cos(Math.toRadians(it.second.hueDegrees)) }
        val hue = (Math.toDegrees(atan2(sine, cosine)) + 360.0) % 360.0
        val spread = percentile(pixels.map { markerHueDistance(it.second.hueDegrees, hue) }, .95)
        if (spread > 12.0 || percentile(saturation, .9) - percentile(saturation, .1) > .35 ||
            percentile(value, .9) - percentile(value, .1) > .40) {
            return invalid("The sample contains mixed colours or uneven light. Fill the box with one flat paper patch.")
        }
        // Body-group labels do not constrain the camera-observed hue. Warm light may make
        // lime look yellow, cyan look greenish, or magenta wrap through red at 0/360 degrees.
        val tolerance = (spread + 8.0).coerceIn(14.0, 24.0)
        val target = MarkerColorTarget(hue, tolerance,
            (percentile(saturation, .1) * .55).coerceIn(.25, .45),
            (percentile(value, .1) * .40).coerceIn(.10, .22), max(6.0, spread + 3.0))
        val inliers = pixels.filter { markerHueDistance(it.second.hueDegrees, hue) <= tolerance }
        val red = inliers.sumOf { (it.first shr 16) and 255 } / inliers.size
        val green = inliers.sumOf { (it.first shr 8) and 255 } / inliers.size
        val blue = inliers.sumOf { it.first and 255 } / inliers.size
        val swatch = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        val candidate = (existing ?: MarkerColorCalibration()).withTarget(color, target)
        for (view in GaitView.entries) if (color in colorsForCalibrationView(view)) {
            candidate.conflictFor(view)?.let { return invalid(it, swatch) }
        }
        return MarkerColorSampleResult(color, target, swatch, "Colour sample ready. Use this colour to confirm.")
    }
}
