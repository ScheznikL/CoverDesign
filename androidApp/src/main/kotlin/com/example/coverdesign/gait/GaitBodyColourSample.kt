package com.example.coverdesign.gait

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** One owned upright, unmirrored camera buffer, retained in memory for manual colour selection. */
internal class GaitColourSnapshot(
    val argb: IntArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val view: GaitView
) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong()) {
            "Snapshot dimensions must match its pixel buffer."
        }
        require(timestampMs >= 0) { "Snapshot timestamp must be monotonic and nonnegative." }
    }
}

/** Learns only the selected 3x3 paper interiors; points never become measured marker positions. */
internal object MarkerBodyColourSample {
    private data class ColourPixel(val argb: Int, val hsv: MarkerHsv)
    private data class Core(val pixels: List<ColourPixel>, val hue: Double)

    fun capture(
        snapshot: GaitColourSnapshot,
        color: MarkerColor,
        points: List<MarkerPoint>
    ): MarkerColorSampleResult {
        fun invalid(message: String) = MarkerColorSampleResult(color, null, 0xff808080.toInt(), message)
        val pair = color == MarkerColor.SHOULDERS || color == MarkerColor.PELVIS || color == MarkerColor.KNEES
        val belongsToView = when (snapshot.view) {
            GaitView.FRONT -> pair
            GaitView.BACK -> color != MarkerColor.KNEES
        }
        if (!belongsToView) return invalid("This marker group belongs to the other view. Select the matching body view.")
        if (points.size != if (pair) 2 else 1) {
            return invalid(if (pair) "Select both markers of this group, one on each side of the body."
                else "Select the centre of the marker.")
        }
        if (snapshot.width < 3 || snapshot.height < 3) return invalid("The camera image is too small. Capture it again.")
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) {
            return invalid("Select marker centres inside the camera image.")
        }
        val centres = points.map { (it.x * snapshot.width).toInt() to (it.y * snapshot.height).toInt() }
        if (centres.any { (x, y) -> x !in 1 until snapshot.width - 1 || y !in 1 until snapshot.height - 1 }) {
            return invalid("The marker is too close to the image edge. Keep its whole centre visible and select it again.")
        }
        if (pair) {
            val first = centres[0]
            val second = centres[1]
            if (hypot((first.first - second.first).toDouble(), (first.second - second.second).toDouble()) < 6.0) {
                return invalid("Choose the other marker on the opposite side of the body.")
            }
        }
        val cores = mutableListOf<Core>()
        for ((x, y) in centres) {
            val retained = mutableListOf<ColourPixel>()
            for (dy in -1..1) for (dx in -1..1) {
                val pixel = snapshot.argb[(y + dy) * snapshot.width + x + dx]
                val hsv = markerHsv(pixel)
                if (hsv.saturation >= .25 && hsv.value >= .12) retained += ColourPixel(pixel, hsv)
            }
            // Two neutral glare pixels are tolerated in a tiny 3x3 core. Requiring 8/9 would
            // reject the real distant shoulder core whose retained median saturation is .331.
            if (retained.size < 7) return invalid("Select the coloured paper centre, away from tape, clothing and glare.")
            if (percentile(retained.map { it.hsv.saturation }, .5) < .32 ||
                percentile(retained.map { it.hsv.value }, .5) < .16) {
                return invalid("The selected paper is too pale or dark. Choose an evenly lit paper centre.")
            }
            val hue = meanHue(retained)
            if (!uniform(retained, hue)) return invalid("The selected centre contains mixed colours or uneven light. Select a smaller, clear paper area.")
            cores += Core(retained, hue)
        }
        if (pair && markerHueDistance(cores[0].hue, cores[1].hue) > 12.0) {
            return invalid("These samples have different colours or lighting. Use matching paper for both sides and select their centres.")
        }
        val pixels = cores.flatMap { it.pixels }
        val hue = meanHue(pixels)
        // Each selected paper must be internally uniform. The pair may have different exposure
        // under a ceiling light; use both real cores instead of rejecting the darker side.
        if (!uniform(pixels, hue, checkValueSpread = !pair)) {
            return invalid("The two paper samples differ too much in colour or lighting. Use matching paper and even light.")
        }
        if (pair && cannotEstablishSeparateRegions(snapshot, centres[0], centres[1], hue)) {
            return invalid("Choose the other marker on the opposite side of the body.")
        }
        val spread = hueSpread(pixels, hue)
        val target = MarkerColorTarget(
            hueDegrees = hue,
            hueToleranceDegrees = (spread + 8.0).coerceIn(14.0, 24.0),
            minSaturation = (percentile(pixels.map { it.hsv.saturation }, .1) * .55).coerceIn(.25, .45),
            minValue = (percentile(pixels.map { it.hsv.value }, .1) * .40).coerceIn(.10, .22),
            minHueToleranceDegrees = max(6.0, spread + 3.0)
        )
        // Keep the swatch tied to real retained RGB pixels, rather than reconstructing a hue.
        val red = pixels.sumOf { (it.argb shr 16) and 255 } / pixels.size
        val green = pixels.sumOf { (it.argb shr 8) and 255 } / pixels.size
        val blue = pixels.sumOf { it.argb and 255 } / pixels.size
        val swatch = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        return MarkerColorSampleResult(color, target, swatch, "Marker colour selected from the body photo.")
    }

    /**
     * Distance in the photograph cannot distinguish two close knees from two taps on one paper.
     * Instead, trace the actual chromatic region containing the first selected core. A finite
     * region that does not reach the other core establishes separate chromatic regions.
     * Large/unbounded colour areas remain ambiguous; the fixed work limit also bounds UI latency
     * and memory independently of camera resolution. No traced pixels enter the colour sample.
     */
    private fun cannotEstablishSeparateRegions(
        snapshot: GaitColourSnapshot,
        first: Pair<Int, Int>,
        second: Pair<Int, Int>,
        hue: Double
    ): Boolean {
        val queue = IntArray(8192)
        val visited = HashSet<Int>()
        val otherCore = HashSet<Int>(9)
        for (dy in -1..1) for (dx in -1..1) {
            otherCore += (second.second + dy) * snapshot.width + second.first + dx
        }
        fun paperPixel(index: Int): Boolean {
            val hsv = markerHsv(snapshot.argb[index])
            return hsv.saturation >= .25 && hsv.value >= .12 && markerHueDistance(hsv.hueDegrees, hue) <= 12.0
        }
        var head = 0
        var tail = 0
        for (dy in -1..1) for (dx in -1..1) {
            val index = (first.second + dy) * snapshot.width + first.first + dx
            if (visited.add(index) && paperPixel(index)) queue[tail++] = index
        }
        while (head < tail) {
            val index = queue[head++]
            if (index in otherCore) return true
            val x = index % snapshot.width
            val y = index / snapshot.width
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until snapshot.width || ny !in 0 until snapshot.height) continue
                val neighbour = ny * snapshot.width + nx
                if (visited.add(neighbour) && paperPixel(neighbour)) {
                    if (tail == queue.size) return true // An unresolved large region is not two confirmed papers.
                    queue[tail++] = neighbour
                }
            }
        }
        return false
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * fraction).toInt()]
    }

    private fun meanHue(pixels: List<ColourPixel>): Double {
        val sine = pixels.sumOf { sin(Math.toRadians(it.hsv.hueDegrees)) }
        val cosine = pixels.sumOf { cos(Math.toRadians(it.hsv.hueDegrees)) }
        return (Math.toDegrees(atan2(sine, cosine)) + 360.0) % 360.0
    }

    private fun hueSpread(pixels: List<ColourPixel>, hue: Double): Double {
        val deviations = pixels.map { markerHueDistance(it.hsv.hueDegrees, hue) }.sorted()
        // With only 7–18 pixels, round upward so a chromatic outlier is not silently discarded.
        return deviations[ceil((deviations.size - 1) * .95).toInt()]
    }

    private fun uniform(pixels: List<ColourPixel>, hue: Double, checkValueSpread: Boolean = true): Boolean =
        hueSpread(pixels, hue) <= 12.0 &&
            percentile(pixels.map { it.hsv.saturation }, .9) - percentile(pixels.map { it.hsv.saturation }, .1) <= .35 &&
            (!checkValueSpread || percentile(pixels.map { it.hsv.value }, .9) - percentile(pixels.map { it.hsv.value }, .1) <= .40)
}
